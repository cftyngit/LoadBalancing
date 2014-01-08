package net.floodlightcontroller.LoadBalancing.Active;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openflow.protocol.OFPacketOut;

import net.floodlightcontroller.LoadBalancing.Active.dijkstra.model.Edge;
import net.floodlightcontroller.LoadBalancing.Active.dijkstra.model.Vertex;
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
	class pairNodePortTuple {
		public NodePortTuple first_;
		public NodePortTuple second_;

		pairNodePortTuple(NodePortTuple first, NodePortTuple second) {
			this.first_ = first;
			this.second_ = second;
		}
	}

	private final int DEFAULT_LINK_WEIGHT = 1;
	protected ILinkDiscoveryService linkDiscovery;
	protected IRoutingService routingEngine;
	protected HashMap<pairNodePortTuple, Set<Route>> pathMap;
	protected HashMap<pairNodePortTuple, Edge> switchLink;
	private List<Edge> links;
	private List<Vertex> switchs;

	@Override
	public Route getRoute(long srcId, short srcPort, long dstId, short dstPort,
			long cookie) {
		// TODO This method will return a load balance routing path
		// If the pair<NodePortTuple> -> set<Route> is empty
		// call findFirstPATH to fast find the first path, and call
		// findOtherPATH in other thread.
		// If pair<NodePortTuple> -> set<Route> is not empty,
		// choose a path from set<Route> by round robin maybe?
		pairNodePortTuple query = new pairNodePortTuple(new NodePortTuple(srcId, srcPort), 
														new NodePortTuple(dstId, dstPort));
		Set<Route> retRoute = pathMap.get(query);
		if (retRoute == null || retRoute.size() == 0) {
			return findFirstPATH(srcId, srcPort, dstId, dstPort);
		} else {

		}
		return null;
	}

	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		// TODO this function will update the switch_connect_map
		System.out.printf("update link = %s\n", update.toString());
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		// TODO this function will update the switch_connect_map
		for (LDUpdate currentUpdate : updateList) {
			// System.out.printf("update list[%d] = %s\n", i,
			// currentUpdate.toString());
			switch (currentUpdate.getOperation()) {
			case LINK_UPDATED: {
				Vertex from = null;
				Vertex to = null;
				for (Vertex currentSwitch : switchs) {
					if (from == null
							&& currentSwitch.getId() == currentUpdate.getSrc()) {
						from = new Vertex(currentSwitch);
						from.setSwitchPort(new NodePortTuple(currentUpdate.getSrc(), currentUpdate.getSrcPort()));
						continue;
					} else if (to == null
							&& currentSwitch.getId() == currentUpdate.getDst()) {
						to = new Vertex(currentSwitch);
						from.setSwitchPort(new NodePortTuple(currentUpdate.getDst(), currentUpdate.getDstPort()));
						continue;
					}
					if (from != null && to != null)
						break;
				}
				if (from != null && to != null) {
					Edge newLink = new Edge(String.valueOf(currentUpdate.getSrc())
							+ "-" + String.valueOf(currentUpdate.getSrcPort())
							+ "to" + String.valueOf(currentUpdate.getDst())
							+ "-" + String.valueOf(currentUpdate.getDstPort()),
							from, to, DEFAULT_LINK_WEIGHT);
					links.add(newLink);
					switchLink.put(new pairNodePortTuple(
							new NodePortTuple(currentUpdate.getSrc(), currentUpdate.getSrcPort()), 
							new NodePortTuple(currentUpdate.getDst(), currentUpdate.getDstPort())), newLink );
				}

			}
				break;
			case LINK_REMOVED: {
				String targetId = String.valueOf(currentUpdate.getSrc()) + "-"
						+ String.valueOf(currentUpdate.getSrcPort()) + "to"
						+ String.valueOf(currentUpdate.getDst()) + "-"
						+ String.valueOf(currentUpdate.getDstPort());
				for (Edge o : links) {
					if (o.getId() == targetId)
						links.remove(o);
				}
				switchLink.remove(new pairNodePortTuple(
							new NodePortTuple(currentUpdate.getSrc(), currentUpdate.getSrcPort()), 
							new NodePortTuple(currentUpdate.getDst(), currentUpdate.getDstPort())));
			}
				break;
			case SWITCH_UPDATED:
				switchs.add(new Vertex(currentUpdate.getSrc(), currentUpdate
						.toString()));
				break;
			case SWITCH_REMOVED:
				switchs.remove(new Vertex(currentUpdate.getSrc(), currentUpdate
						.toString()));
				break;
			default:
				continue;
			}
		}
	}

	public Route findFirstPATH(long srcId, short srcPort, long dstId,
			short dstPort) {
		// TODO this function will use the default routing module to find the
		// first path
		// and add this path to the pair<NodePortTuple> -> set<Route>
		return routingEngine.getRoute(srcId, srcPort, dstId, dstPort, 0);
	}

	public Route findOtherPATH(long srcId, short srcPort, long dstId,
			short dstPort, long count) {
		// TODO this function will find many path by some algorithm, and
		// add them into the set<Route>
		// This function should run in other thread
		// remember to use mutex

		return null;
	}

	private Route getRouteFromSet(Set<Route> routes) {
		Collections.sort(links, new Comparator<Edge>() {
			public int compare(Edge o1, Edge o2) {
				return o2.getWeight() - o1.getWeight();
			}
		});

		return null;
	}

	public void updateweight(Route path) {
		// TODO this function will update the link weight in
		// switch_connect_map(if any)

	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		// l.add(ITopologyService.class);
		l.add(ILoadbalanceRoutingService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
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
