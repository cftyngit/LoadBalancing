package net.floodlightcontroller.NSLActiveLB.Active;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.HexString;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.counter.ICounterStoreService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.loadbalancer.LoadBalancer;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.OFMessageDamper;
import net.floodlightcontroller.ActiveLB.ILoadbalanceRoutingService;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;

public class ActiveLoadBalancer implements IFloodlightModule,
		IOFMessageListener {
	
	protected static Logger log = LoggerFactory.getLogger(LoadBalancer.class);
	
	protected IFloodlightProviderService floodlightProvider;
	protected IDeviceService deviceManager;
	protected ITopologyService topology;
	protected ILoadbalanceRoutingService routingEngine;
	protected IStaticFlowEntryPusherService sfp;
    protected ICounterStoreService counterStore;
    protected OFMessageDamper messageDamper;
    
    //Copied from Forwarding with message damper routine for pushing proxy Arp 
    protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // ms. 
    protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms 
	protected static int LB_PRIORITY = 10235;
	
	@Override
	public String getName() {
		return "activeLoadbalancer";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// copy form LoadBalancer, and our active loadbalancer should run behind build-in loadbalancer 
		return (type.equals(OFType.PACKET_IN) && 
               (name.equals("topology") || 
               name.equals("devicemanager") ||
               name.equals("loadbalancer") ||
               name.equals("virtualizer")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// copy form LoadBalancer
		return (type.equals(OFType.PACKET_IN) && name.equals("forwarding"));
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
        case PACKET_IN:
            return processPacketIn(sw, (OFPacketIn)msg, cntx);
        default:
            break;
		}
		return Command.CONTINUE;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}
    public Comparator<SwitchPort> clusterIdComparator =
            new Comparator<SwitchPort>() {
                @Override
                public int compare(SwitchPort d1, SwitchPort d2) {
                    Long d1ClusterId = 
                            topology.getL2DomainId(d1.getSwitchDPID());
                    Long d2ClusterId = 
                            topology.getL2DomainId(d2.getSwitchDPID());
                    return d1ClusterId.compareTo(d2ClusterId);
                }
            };
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ILoadbalanceRoutingService.class);
		l.add(ITopologyService.class);
		l.add(IStaticFlowEntryPusherService.class);
		l.add(ICounterStoreService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		deviceManager = context.getServiceImpl(IDeviceService.class);
		topology = context.getServiceImpl(ITopologyService.class);
		routingEngine = context.getServiceImpl(ILoadbalanceRoutingService.class);
		sfp = context.getServiceImpl(IStaticFlowEntryPusherService.class);
		counterStore = context.getServiceImpl(ICounterStoreService.class);
		messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY, 
                EnumSet.of(OFType.FLOW_MOD),
                OFMESSAGE_DAMPER_TIMEOUT);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);

	}
	private net.floodlightcontroller.core.IListener.Command
    processPacketIn(IOFSwitch sw, OFPacketIn pi,
                    FloodlightContext cntx)
	{
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPacket pkt = eth.getPayload();
		
		if (pkt instanceof IPv4) {
			
	        IPv4 ip_pkt = (IPv4) pkt;
	        int destIpAddress = ip_pkt.getDestinationAddress();
	        int srcIpAddress = ip_pkt.getSourceAddress();
	        pushBidirectionalVipRoutes(sw, pi, cntx, destIpAddress, srcIpAddress);
	        
	        // packet out based on table rule
	        pushPacket(pkt, sw, pi.getBufferId(), pi.getInPort(), OFPort.OFPP_TABLE.getValue(),
                    cntx, true);
	        System.out.println("go into ActiveLoadBalancer && goto stop");
	        return Command.STOP;
		}
		// bypass non-load-balanced traffic for normal processing (forwarding)
		System.out.println("go into ActiveLoadBalancer && goto continue");
		return Command.CONTINUE;
	}
	
	protected void pushBidirectionalVipRoutes(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, int destIpAddress, int srcIpAddress) {
		Collection<? extends IDevice> allDevices = deviceManager
                .getAllDevices();
		IDevice srcDevice = null;
        IDevice dstDevice = null;
        for (IDevice d : allDevices) {
            for (int j = 0; j < d.getIPv4Addresses().length; j++) {
                    if (srcDevice == null && srcIpAddress == d.getIPv4Addresses()[j])
                        srcDevice = d;
                    if (dstDevice == null && destIpAddress == d.getIPv4Addresses()[j]) {
                        dstDevice = d;
                        //member.macString = dstDevice.getMACAddressString();
                    }
                    if (srcDevice != null && dstDevice != null)
                        break;
            }
        }  
        
     // srcDevice and/or dstDevice is null, no route can be pushed
        if (srcDevice == null || dstDevice == null) return;
        
        Long srcIsland = topology.getL2DomainId(sw.getId());

        if (srcIsland == null) {
            /*log.debug("No openflow island found for source {}/{}", 
                      sw.getStringId(), pi.getInPort());
                      */
            return;
        }
        
        // Validate that we have a destination known on the same island
        // Validate that the source and destination are not on the same switchport
        boolean on_same_island = false;
        boolean on_same_if = false;
        for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
            long dstSwDpid = dstDap.getSwitchDPID();
            Long dstIsland = topology.getL2DomainId(dstSwDpid);
            if ((dstIsland != null) && dstIsland.equals(srcIsland)) {
                on_same_island = true;
                if ((sw.getId() == dstSwDpid) &&
                        (pi.getInPort() == dstDap.getPort())) {
                    on_same_if = true;
                }
                break;
            }
        }
        if (!on_same_island) {
            // Flood since we don't know the dst device
            
            return;
        }            
        
        if (on_same_if) {
            
            return;
        }
        
     // Install all the routes where both src and dst have attachment
        // points.  Since the lists are stored in sorted order we can 
        // traverse the attachment points in O(m+n) time
        SwitchPort[] srcDaps = srcDevice.getAttachmentPoints();
        Arrays.sort(srcDaps, clusterIdComparator);
        SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
        Arrays.sort(dstDaps, clusterIdComparator);
        
        int iSrcDaps = 0, iDstDaps = 0;

        // following Forwarding's same routing routine, retrieve both in-bound and out-bound routes for
        // all clusters.
        while ((iSrcDaps < srcDaps.length) && (iDstDaps < dstDaps.length)) {
            SwitchPort srcDap = srcDaps[iSrcDaps];
            SwitchPort dstDap = dstDaps[iDstDaps];
            Long srcCluster = 
                    topology.getL2DomainId(srcDap.getSwitchDPID());
            Long dstCluster = 
                    topology.getL2DomainId(dstDap.getSwitchDPID());

            int srcVsDest = srcCluster.compareTo(dstCluster);
            if (srcVsDest == 0) {
                if (!srcDap.equals(dstDap) && 
                        (srcCluster != null) && 
                        (dstCluster != null)) {
                    Route routeIn = 
                            routingEngine.getRoute(srcDap.getSwitchDPID(),
                                                   (short)srcDap.getPort(),
                                                   dstDap.getSwitchDPID(),
                                                   (short)dstDap.getPort(), 0);
                    Route routeOut = 
                            routingEngine.getRoute(dstDap.getSwitchDPID(),
                                                   (short)dstDap.getPort(),
                                                   srcDap.getSwitchDPID(),
                                                   (short)srcDap.getPort(), 0);

                    // use static flow entry pusher to push flow mod along in and out path
                    // in: match src client (ip, port), rewrite dest from vip ip/port to member ip/port, forward
                    // out: match dest client (ip, port), rewrite src from member ip/port to vip ip/port, forward
                    
                    if (routeIn != null) {
                        pushStaticVipRoute(true, routeIn, srcIpAddress, destIpAddress, sw.getId());
                    }
                    
                    if (routeOut != null) {
                        pushStaticVipRoute(false, routeOut, srcIpAddress, destIpAddress, sw.getId());
                    }

                }
                iSrcDaps++;
                iDstDaps++;
            } else if (srcVsDest < 0) {
                iSrcDaps++;
            } else {
                iDstDaps++;
            }
        }
        return;
	}
    /**
     * used to push given route using static flow entry pusher
     * @param boolean inBound
     * @param Route route
     * @param IPClient client
     * @param LBMember member
     * @param long pinSwitch
     */
    public void pushStaticVipRoute(boolean inBound, Route route, int ipFrom, int ipTo, long pinSwitch) {
        List<NodePortTuple> path = route.getPath();
        if (path.size()>0) {
           for (int i = 0; i < path.size(); i+=2) {
               
               long sw = path.get(i).getNodeId();
               String swString = HexString.toHexString(path.get(i).getNodeId());
               String entryName;
               String matchString = null;
               String actionString = null;
               
               OFFlowMod fm = (OFFlowMod) floodlightProvider.getOFMessageFactory()
                       .getMessage(OFType.FLOW_MOD);

               fm.setIdleTimeout((short) 0);   // infinite
               fm.setHardTimeout((short) 0);   // infinite
               fm.setBufferId(OFPacketOut.BUFFER_ID_NONE);
               fm.setCommand((short) 0);
               fm.setFlags((short) 0);
               fm.setOutPort(OFPort.OFPP_NONE.getValue());
               fm.setCookie((long) 0);  
               fm.setPriority(Short.MAX_VALUE);
               
               if (inBound) {
                   entryName = "inbound-from-ip-"+ ipFrom+"-to-ip-"+ipTo
                           +"-srcswitch-"+path.get(0).getNodeId()+"-sw-"+sw;
                   matchString = "nw_src="+IPv4.fromIPv4Address(ipFrom)+","
                               + "nw_dst="+IPv4.fromIPv4Address(ipTo)+","
                               + "in_port="+String.valueOf(path.get(i).getPortId());

                   if (sw == pinSwitch) {
                       actionString = "output="+path.get(i+1).getPortId();
                   } else {
                       actionString =
                               "output="+path.get(i+1).getPortId();
                   }
               } else {
                   entryName = "outbound-from-ip-"+ ipTo+"-to-ip-"+ipFrom
                           +"-srcswitch-"+path.get(0).getNodeId()+"-sw-"+sw;
                   matchString = "nw_dst="+IPv4.fromIPv4Address(ipFrom)+","
                		       + "nw_src="+IPv4.fromIPv4Address(ipTo)+","
                               + "in_port="+String.valueOf(path.get(i).getPortId());

                   if (sw == pinSwitch) {
                       actionString = "output="+path.get(i+1).getPortId();
                   } else {
                       actionString = "output="+path.get(i+1).getPortId();
                   }
                   
               }
               
               parseActionString(fm, actionString, log);

               fm.setPriority(U16.t(LB_PRIORITY));

               OFMatch ofMatch = new OFMatch();
               try {
                   ofMatch.fromString(matchString);
               } catch (IllegalArgumentException e) {
                   log.debug("ignoring flow entry {} on switch {} with illegal OFMatch() key: "
                                     + matchString, entryName, swString);
               }
        
               fm.setMatch(ofMatch);
               sfp.addFlow(entryName, fm, swString);

           }
        }
        return;
    }
    
    // Utilities borrowed from StaticFlowEntries
    
    private static class SubActionStruct {
        OFAction action;
        int      len;
    }
    
    /**
     * Parses OFFlowMod actions from strings.
     * @param flowMod The OFFlowMod to set the actions for
     * @param actionstr The string containing all the actions
     * @param log A logger to log for errors.
     */
    public static void parseActionString(OFFlowMod flowMod, String actionstr, Logger log) {
        List<OFAction> actions = new LinkedList<OFAction>();
        int actionsLength = 0;
        if (actionstr != null) {
            actionstr = actionstr.toLowerCase();
            for (String subaction : actionstr.split(",")) {
                String action = subaction.split("[=:]")[0];
                SubActionStruct subaction_struct = null;
                
                if (action.equals("output")) {
                    subaction_struct = decode_output(subaction, log);
                }
                else {
                    log.error("Unexpected action '{}', '{}'", action, subaction);
                }
                
                if (subaction_struct != null) {
                    actions.add(subaction_struct.action);
                    actionsLength += subaction_struct.len;
                }
            }
        }
        log.debug("action {}", actions);
        
        flowMod.setActions(actions);
        flowMod.setLengthU(OFFlowMod.MINIMUM_LENGTH + actionsLength);
    } 
    
    private static SubActionStruct decode_output(String subaction, Logger log) {
        SubActionStruct sa = null;
        Matcher n;
        
        n = Pattern.compile("output=(?:((?:0x)?\\d+)|(all)|(controller)|(local)|(ingress-port)|(normal)|(flood))").matcher(subaction);
        if (n.matches()) {
            OFActionOutput action = new OFActionOutput();
            action.setMaxLength((short) Short.MAX_VALUE);
            short port = OFPort.OFPP_NONE.getValue();
            if (n.group(1) != null) {
                try {
                    port = get_short(n.group(1));
                }
                catch (NumberFormatException e) {
                    log.debug("Invalid port in: '{}' (error ignored)", subaction);
                    return null;
                }
            }
            else if (n.group(2) != null)
                port = OFPort.OFPP_ALL.getValue();
            else if (n.group(3) != null)
                port = OFPort.OFPP_CONTROLLER.getValue();
            else if (n.group(4) != null)
                port = OFPort.OFPP_LOCAL.getValue();
            else if (n.group(5) != null)
                port = OFPort.OFPP_IN_PORT.getValue();
            else if (n.group(6) != null)
                port = OFPort.OFPP_NORMAL.getValue();
            else if (n.group(7) != null)
                port = OFPort.OFPP_FLOOD.getValue();
            action.setPort(port);
            log.debug("action {}", action);
            
            sa = new SubActionStruct();
            sa.action = action;
            sa.len = OFActionOutput.MINIMUM_LENGTH;
        }
        else {
            log.error("Invalid subaction: '{}'", subaction);
            return null;
        }
        
        return sa;
    }
    private static short get_short(String str) {
        return (short)(int)Integer.decode(str);
    }
    /**
     * used to push any packet - borrowed routine from Forwarding
     * 
     * @param OFPacketIn pi
     * @param IOFSwitch sw
     * @param int bufferId
     * @param short inPort
     * @param short outPort
     * @param FloodlightContext cntx
     * @param boolean flush
     */    
    public void pushPacket(IPacket packet, 
                           IOFSwitch sw,
                           int bufferId,
                           short inPort,
                           short outPort, 
                           FloodlightContext cntx,
                           boolean flush) {
        if (log.isTraceEnabled()) {
            log.trace("PacketOut srcSwitch={} inPort={} outPort={}", 
                      new Object[] {sw, inPort, outPort});
        }

        OFPacketOut po =
                (OFPacketOut) floodlightProvider.getOFMessageFactory()
                                                .getMessage(OFType.PACKET_OUT);

        // set actions
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(outPort, (short) 0xffff));

        po.setActions(actions)
          .setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);
        short poLength =
                (short) (po.getActionsLength() + OFPacketOut.MINIMUM_LENGTH);

        // set buffer_id, in_port
        po.setBufferId(bufferId);
        po.setInPort(inPort);

        // set data - only if buffer_id == -1
        if (po.getBufferId() == OFPacketOut.BUFFER_ID_NONE) {
            if (packet == null) {
                log.error("BufferId is not set and packet data is null. " +
                          "Cannot send packetOut. " +
                        "srcSwitch={} inPort={} outPort={}",
                        new Object[] {sw, inPort, outPort});
                return;
            }
            byte[] packetData = packet.serialize();
            poLength += packetData.length;
            po.setPacketData(packetData);
        }

        po.setLength(poLength);

        try {
            counterStore.updatePktOutFMCounterStoreLocal(sw, po);
            messageDamper.write(sw, po, cntx, flush);
        } catch (IOException e) {
            log.error("Failure writing packet out", e);
        }
    }
}
