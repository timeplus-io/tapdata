package com.tapdata.entity;

import java.io.Serializable;
import java.util.List;

/**
 * @author samuel
 * @Description
 * @create 2022-05-19 14:57
 **/
public class TapdataHeartbeatEvent extends TapdataEvent implements Serializable, Cloneable {

	private static final long serialVersionUID = -8235448692720473757L;

	public TapdataHeartbeatEvent() {
	}

	public static TapdataHeartbeatEvent create(Long timestamp, Object streamOffset) {
		TapdataHeartbeatEvent tapdataHeartbeatEvent = new TapdataHeartbeatEvent();
		tapdataHeartbeatEvent.setSourceTime(timestamp);
		tapdataHeartbeatEvent.setStreamOffset(streamOffset);
		return tapdataHeartbeatEvent;
	}

	public static TapdataHeartbeatEvent create(Long timestamp, Object streamOffset, List<String> nodeIds) {
		TapdataHeartbeatEvent tapdataHeartbeatEvent = new TapdataHeartbeatEvent();
		tapdataHeartbeatEvent.setSourceTime(timestamp);
		tapdataHeartbeatEvent.setStreamOffset(streamOffset);
		tapdataHeartbeatEvent.setNodeIds(nodeIds);
		return tapdataHeartbeatEvent;
	}

	@Override
	public Object clone() {
		TapdataEvent tapdataEvent = TapdataHeartbeatEvent.create(getSourceTime(), getStreamOffset(), getNodeIds());
		super.clone(tapdataEvent);
		return tapdataEvent;
	}
}
