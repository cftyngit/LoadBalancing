package net.floodlightcontroller.LoadBalancing.Active;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openflow.protocol.OFPacketOut;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.LinkInfo;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;

public class LoadbalanceRouting implements ILinkDiscoveryListener,
		ILoadbalanceRoutingService, IFloodlightModule {
	class pairNodePortTuple
	{
		public NodePortTuple first;
		public NodePortTuple second;
	}
	
	protected ILinkDiscoveryService linkDiscovery;
	protected IRoutingService routingEngine;
	protected HashMap<pairNodePortTuple, Set<Route> > pathMap;
	
	@Override
	public Route getRoute(long srcId, short srcPort, long dstId, short dstPort,
			long cookie) {
		// TODO This method will return a load balance routing path
		//      If the pair<NodePortTuple> -> set<Route> is empty
		//		call findFirstPATH to fast find the first path, and call 
		//		findOtherPATH in other thread.
		//		If pair<NodePortTuple> -> set<Route> is not empty, 
		//		choose a path from set<Route> by round robin maybe?
		return findFirstPATH(srcId, srcPort, dstId, dstPort);
	}

	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		// TODO this function will update the switch_connect_map
		
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		// TODO this function will update the switch_connect_map
	}

	public Route findFirstPATH(long srcId, short srcPort, long dstId, short dstPort) {
		// TODO this function will use the default routing module to find the first path
		//      and add this path to the pair<NodePortTuple> -> set<Route>
		return routingEngine.getRoute(srcId, srcPort, dstId, dstPort, 0);
	}
	
	public Route findOtherPATH(long srcId, short srcPort, long dstId, short dstPort, long count) {
		// TODO this function will find many path by some algorithm, and
		//		add them into the set<Route>
		//		This function should run in other thread
		//		remember to use mutex
		return null;
	}
	
	public void updateweight(Route path) {
		// TODO this function will update the link weight in 
		//		switch_connect_map(if any)
		
	}
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
                new ArrayList<Class<? extends IFloodlightService>>();
        //l.add(ITopologyService.class);
        l.add(ILoadbalanceRoutingService.class);
        return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
        IFloodlightService> m =
            new HashMap<Class<? extends IFloodlightService>,
                IFloodlightService>();
        // We are the class that implements the service
        m.put(ILoadbalanceRoutingService.class, this);
        return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(ITopologyService.class);
		l.add(ILinkDiscoveryService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		linkDiscovery = context.getServiceImpl(ILinkDiscoveryService.class);
		routingEngine = context.getServiceImpl(IRoutingService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		// TODO Auto-generated method stub
		linkDiscovery.addListener(this);
	}
}
