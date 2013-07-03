package com.lunatech.bpm.server.workitem;

import java.util.Iterator;

import org.drools.runtime.process.NodeInstance;
import org.drools.runtime.process.ProcessInstance;
import org.drools.runtime.process.WorkItem;
import org.drools.runtime.process.WorkItemManager;
import org.jbpm.workflow.instance.WorkflowProcessInstance;
import org.jbpm.workflow.instance.node.WorkItemNodeInstance;

import com.lunatech.bpm.server.AdminController;

public class MyService implements RetriableWorkItemHandler {

	WorkItem  workItem;
	static int ok = 0;
	
	public MyService() {
	}
	
	@Override
	public void abortWorkItem(WorkItem workItem, WorkItemManager manager) {
		System.out.println("Abort");
	}

	@Override
	public void executeWorkItem(WorkItem workItem, WorkItemManager manager) {
		System.out.println("Ok here we go");
		this.workItem = workItem;
		// Simulate an exception
		doBusinessProcess(workItem);
		manager.completeWorkItem(workItem.getId(), null);
	}


	public void doBusinessProcess(WorkItem workItem) {
		try {
			System.out.println("I am not working");
			System.out.println(workItem.getParameter("X").toString());
		} catch(Exception e) {
			System.out.println("Roger we have an exception, abording process id " + workItem.getProcessInstanceId() + " for workitem " + workItem.getId());
			// Mark the workflow as suspended since  
			ProcessInstance processInstance = AdminController.ksession.getProcessInstance(workItem.getProcessInstanceId());
			((WorkflowProcessInstance)processInstance).setState(ProcessInstance.STATE_SUSPENDED);
			Iterator<NodeInstance> iter = ((WorkflowProcessInstance)processInstance).getNodeInstances().iterator();
			String currentnode = ","; 
			while (iter.hasNext()) {
				NodeInstance node = iter.next();
				currentnode += node.getNodeId();
			}
			currentnode = currentnode.substring(1);
			// Mark it as a candidate for reprocessing
			AdminController.workItemToReprocess.put(workItem.getProcessInstanceId() + "-" + currentnode, this);
		}		

	}

	public WorkItem getWorkItem() {
		return workItem;

	}
	
}
