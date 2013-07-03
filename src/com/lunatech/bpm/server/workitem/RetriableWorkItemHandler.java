package com.lunatech.bpm.server.workitem;

import org.drools.process.instance.WorkItemHandler;
import org.drools.runtime.process.WorkItem;

public interface RetriableWorkItemHandler extends WorkItemHandler {
	
	public void doBusinessProcess(WorkItem workItem);

	public WorkItem getWorkItem();
}
