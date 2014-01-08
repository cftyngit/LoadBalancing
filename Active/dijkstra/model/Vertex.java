package net.floodlightcontroller.LoadBalancing.Active.dijkstra.model;

import net.floodlightcontroller.topology.NodePortTuple;

public class Vertex {
	final private long id;
	final private String name;
	private NodePortTuple nodePort;

	public Vertex(long id, String name) {
		this.nodePort = new NodePortTuple(0, 0);
		this.id = id;
		this.name = name;
	}

	public Vertex(long id, String name, NodePortTuple switchPort) {
		this.nodePort = switchPort;
		this.id = id;
		this.name = name;
	}

	public Vertex(Vertex currentSwitch) {
		this.id = currentSwitch.id;
		this.name = new String(currentSwitch.name);
		this.nodePort = new NodePortTuple(currentSwitch.nodePort.getNodeId(), currentSwitch.nodePort.getPortId());
	}

	public long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public NodePortTuple getSwitchPort() {
		return nodePort;
	}

	public void setSwitchPort(NodePortTuple npt) {
		nodePort = npt;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		Long ID = id;
		result = prime * result + ((id == 0) ? 0 : ID.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Vertex other = (Vertex) obj;
		if (id == 0) {
			if (other.id != 0)
				return false;
		} else if (id != other.id)
			return false;
		return true;
	}

	@Override
	public String toString() {
		return name;
	}

}
