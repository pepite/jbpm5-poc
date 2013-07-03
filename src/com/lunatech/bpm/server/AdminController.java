package com.lunatech.bpm.server;


import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import org.drools.KnowledgeBase;
import org.drools.SystemEventListenerFactory;
import org.drools.builder.KnowledgeBuilder;
import org.drools.builder.KnowledgeBuilderFactory;
import org.drools.builder.ResourceType;
import org.drools.io.ResourceFactory;
import org.drools.process.instance.WorkItem;
import org.drools.runtime.StatefulKnowledgeSession;
import org.drools.runtime.process.NodeInstance;
import org.drools.runtime.process.ProcessInstance;
import org.jbpm.process.workitem.wsht.MinaHTWorkItemHandler;
import org.jbpm.task.Group;
import org.jbpm.task.User;
import org.jbpm.task.query.TaskSummary;
import org.jbpm.task.service.ContentData;
import org.jbpm.task.service.TaskClient;
import org.jbpm.task.service.TaskService;
import org.jbpm.task.service.hornetq.HornetQTaskServer;
import org.jbpm.task.service.mina.MinaTaskClientConnector;
import org.jbpm.task.service.mina.MinaTaskClientHandler;
import org.jbpm.task.service.mina.MinaTaskServer;
import org.jbpm.task.service.responsehandlers.BlockingTaskOperationResponseHandler;
import org.jbpm.task.service.responsehandlers.BlockingTaskSummaryResponseHandler;
import org.jbpm.workflow.instance.WorkflowProcessInstance;
import org.jbpm.workflow.instance.node.WorkItemNodeInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import com.lunatech.bpm.server.workitem.MyFirstService;
import com.lunatech.bpm.server.workitem.MyService;
import com.lunatech.bpm.server.workitem.RetriableWorkItemHandler;

/**
 * TODO Refactor
 * @author nicolas
 *
 */
@Controller
public class AdminController  {

	protected static final Logger log = LoggerFactory
			.getLogger(AdminController.class.getName());

	// Bootstrap our jbpm session
	public static KnowledgeBase kb = readKnowledgeBase("sample.bpmn", "sample-with-user-task.bpmn");
	
	// Our stateful knowledge session. This is a singleton
	public static StatefulKnowledgeSession ksession = getKnowledgeSession(kb);
	
	// The primary key is always 'process-instance-id'-'workitem-id'
	public static Map<String, RetriableWorkItemHandler> workItemToReprocess = new HashMap<String, RetriableWorkItemHandler>();
	
	EntityManagerFactory emf ;
	TaskService taskService;
	
	// Start the Human Task server
	HornetQTaskServer server; 
			
	
	
	public AdminController() {
		
	}
	
	
	@PreDestroy
	public void contextDestroyed() {
		// TODO Auto-generated method stub
		try {
			log.info("shutting down service task");
			server.stop();
			emf.close();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}


	@PostConstruct
	public void init() {
		log.info("initialize service task");
		emf = Persistence.createEntityManagerFactory("org.jbpm.task");
		EntityManagerFactory emf = Persistence.createEntityManagerFactory("org.jbpm.task");

		// when building TaskService provide escalation handler as argument

		TaskService taskService = new TaskService(emf, SystemEventListenerFactory.getSystemEventListener());

		MinaTaskServer server = new MinaTaskServer( taskService );

		Thread thread = new Thread( server );

		thread.start();
		
		Map<String, Group> groups = new HashMap<String, Group>();
		groups.put("management", new Group("management"));
		Map<String, User> users = new HashMap<String, User>();
		users.put("krisv", new User("krisv"));
		users.put("nicolas", new User("nicolas"));
		
		// Mandatory
		users.put("Administrator", new User("Administrator"));
		taskService.addUsersAndGroups(users, groups);
		
	}
	
	
	@ResponseBody
	@RequestMapping(value = "/admin/process-instances/list", method = RequestMethod.GET)
	public ResponseEntity<String> listProcessIntances() {
		try {
			// List the directory containing the layout
			String output = "<process-instances>";
			for (ProcessInstance process : ksession.getProcessInstances()) {
				Iterator<NodeInstance> iter = ((WorkflowProcessInstance)process).getNodeInstances().iterator();
				String currentnode = ","; 
				while (iter.hasNext()) {
					NodeInstance node = iter.next();
					currentnode += node.getNodeId();
				}
				currentnode = currentnode.substring(1);
				output += "<process-instance id=\"" + process.getId() + "\" name=\"" + process.getProcessName() + "\" process-id=\"" + process.getProcessId() 
						+ "\" status=\"" + getStatus(process) + "\" current-node=\"" + currentnode + "\"/>";  
			}
			output += "</process-instances>";
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	@ResponseBody
	@RequestMapping(value = "/admin/tasks/{name}/list", method = RequestMethod.GET)
	public ResponseEntity<String> listTasks(@PathVariable String name) {
		try {
			// List the directory containing the layout
			String output = "<tasks>";
			TaskClient client = new TaskClient(new MinaTaskClientConnector("Test", new MinaTaskClientHandler(SystemEventListenerFactory.getSystemEventListener())));
			client.connect();

			// TODO: manage users
			BlockingTaskSummaryResponseHandler responseHandler = new BlockingTaskSummaryResponseHandler();
			
			client.getTasksOwned(name, "en-UK", responseHandler);
			//client.getTasksAssignedAsPotentialOwner(name, "en-UK", responseHandler);
			
			List<TaskSummary> tasks = responseHandler.getResults();
			
			for (TaskSummary task : tasks) {
				output += "<task process-instance-id=\"" + task.getProcessInstanceId() + "\" id=\"" + task.getId() + "\" subject=\""+ task.getSubject() + "\" status=\""+ task.getStatus() + "\" owner=\"" + task.getActualOwner() + "\" name=\"" + task.getName() + "\"/>";
			}
			output += "</tasks>";
			client.disconnect();
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	
	@ResponseBody
	@RequestMapping(value = "/admin/tasks/{id}/complete", method = RequestMethod.GET)
	public ResponseEntity<String> completeTask(@PathVariable String id) {
		try {
			// List the directory containing the layout
			String output = "<tasks>";
			TaskClient client = new TaskClient(new MinaTaskClientConnector("Test", new MinaTaskClientHandler(SystemEventListenerFactory.getSystemEventListener())));
			client.connect();

			// TODO: manage users
//			BlockingGetTaskResponseHandler responseHandler = new BlockingGetTaskResponseHandler();
//			client.getTask(Long.valueOf(id), responseHandler);
//			
//			Task task = responseHandler.getTask();
			
			BlockingTaskOperationResponseHandler responseHandler = new BlockingTaskOperationResponseHandler();
			 
	        responseHandler.waitTillDone(5000);
			client.start(Long.valueOf(id), "nicolas", responseHandler);
			Thread.sleep(1000);
			client.complete(Long.valueOf(id), "nicolas", new ContentData(), responseHandler);
			
			output += "</tasks>";
			client.disconnect();
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@ResponseBody
	@RequestMapping(value = "/admin/process-definitions/list", method = RequestMethod.GET)
	public ResponseEntity<String> listProcessDefinitions() {
		try {
			// List the directory containing the layout
			String output = "<process-definitions>";
			for (org.drools.definition.process.Process process : kb.getProcesses()) {
				output += "<process-definition id=\"" + process.getId() + "\" name=\"" + process.getName() + "\" version=\"" + process.getVersion() + "\" />";  
			}
			output += "</process-definitions>";
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}

	@ResponseBody
	@RequestMapping(value = "/admin/process-instances/{name}/start", method = RequestMethod.GET)
	public ResponseEntity<String> startProcess(@PathVariable String name) {
		try {
			final WorkflowProcessInstance processInstance = (WorkflowProcessInstance)ksession.createProcessInstance(name, null);
			ksession = addWorkItemHandlders(ksession);
			
			// TODO: This should be handled by a thread pool
			new Thread(new Runnable() {
				
				@Override
				public void run() {
					ksession.startProcessInstance(processInstance.getId());
				}
			}).start();
			// List the directory containing the layout
			String output = "<result>" + processInstance.getId() + "</result>";
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	@ResponseBody
	@RequestMapping(value = "/admin/process-instances/{id}/node-name/{name}/retry", method = RequestMethod.GET)
	public ResponseEntity<String> retryNodeByName(@PathVariable String id, @PathVariable String name) {
		try{
			// TODO: add a way to update the work item to correct data
			final WorkflowProcessInstance processInstance = (WorkflowProcessInstance)ksession.getProcessInstance(Long.valueOf(id));
			// Get the node
			Iterator<NodeInstance> iter = processInstance.getNodeInstances().iterator();
			String nodeid = "";
			while (iter.hasNext()) {
				NodeInstance node = iter.next();
				if (name.equals(node.getNodeName())) {
					if (node instanceof WorkItemNodeInstance) {
						WorkItemNodeInstance instance = (WorkItemNodeInstance)node;
						WorkItem item = instance.getWorkItem();
						processInstance.setState(ProcessInstance.STATE_ACTIVE);
						// Get our workItem to reprocess
						RetriableWorkItemHandler toReprocess = workItemToReprocess.get(id + "-" + nodeid);
						workItemToReprocess.remove(id + "-" + nodeid);
						if (toReprocess != null) {
							((RetriableWorkItemHandler)toReprocess).doBusinessProcess(item);
							nodeid = String.valueOf(item.getId());
						}
					}
				}
			}
			
			String output = "<result>" + nodeid + "</result>";
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	@ResponseBody
	@RequestMapping(value = "/admin/process-instances/{id}/node-id/{nodeid}/retry", method = RequestMethod.GET)
	public ResponseEntity<String> retryNodeById(@PathVariable String id, @PathVariable String nodeid) {
		try{
			// TODO: add a way to update the work item to correct data
			final WorkflowProcessInstance processInstance = (WorkflowProcessInstance)ksession.getProcessInstance(Long.valueOf(id));
			// Get the node
			Iterator<NodeInstance> iter = processInstance.getNodeInstances().iterator();
			while (iter.hasNext()) {
				NodeInstance node = iter.next();
				if (Long.valueOf(nodeid).equals(node.getNodeId())) {
					if (node instanceof WorkItemNodeInstance) {
						WorkItemNodeInstance instance = (WorkItemNodeInstance)node;
						WorkItem item = instance.getWorkItem();
						// Get our workItem to reprocess
						RetriableWorkItemHandler toReprocess = workItemToReprocess.get(id + "-" + nodeid);
						workItemToReprocess.remove(id + "-" + nodeid);
						log.info(" retrying " + id + "-" + nodeid + " " + toReprocess);
						if (toReprocess != null) {
							((RetriableWorkItemHandler)toReprocess).doBusinessProcess(item);
							nodeid = String.valueOf(item.getId());
						}
					}
				}
			}
			
			String output = "<result>" + nodeid + "</result>";
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	@ResponseBody
	@RequestMapping(value = "/admin/process-instances/{id}", method = RequestMethod.GET)
	public ResponseEntity<String> processStatus(@PathVariable String id) {
		try {
			final WorkflowProcessInstance processInstance = (WorkflowProcessInstance)ksession.getProcessInstance(Long.valueOf(id));
			// List the directory containing the layout
			String output = "<result><status>";
			if (processInstance != null) {
			
				output += getStatus(processInstance) + "</status><nodes>";
				Iterator<NodeInstance> iter = processInstance.getNodeInstances().iterator();
				while (iter.hasNext()) {
					NodeInstance node = iter.next();
					output += "<node id=\"" + node.getNodeId() + "\">" + node.getNodeName() + "</node>";
				}
				output += "</nodes></result>";
			} else {
				output += "NOT_FOUND</status></result>";
			}
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.APPLICATION_XML);
			return new ResponseEntity<String>(output, responseHeaders, HttpStatus.OK);
		} catch(Exception e) {
			log.error("ss ", e);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.TEXT_PLAIN);
			return new ResponseEntity<String>(" unexpected error " + e , responseHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
	
	private String getStatus(ProcessInstance processInstance) {
		switch(processInstance.getState()) {
		case ProcessInstance.STATE_ABORTED:
			return "STATE_ABORTED";
		case ProcessInstance.STATE_ACTIVE:
			return "STATE_ACTIVE";
		case ProcessInstance.STATE_COMPLETED:
			return "STATE_COMPLETED";
		case ProcessInstance.STATE_PENDING:
			return "STATE_PENDING";
		case ProcessInstance.STATE_SUSPENDED:
			return "STATE_SUSPENDED";
		}
		return "STATE_UNKNOWN";
	}


	protected StatefulKnowledgeSession addWorkItemHandlders(StatefulKnowledgeSession ksession) {
		ksession.getWorkItemManager().registerWorkItemHandler("MyService", new MyService());
		ksession.getWorkItemManager().registerWorkItemHandler("MyFirstService", new MyFirstService());
		MinaHTWorkItemHandler handler = new MinaHTWorkItemHandler(ksession);
		ksession.getWorkItemManager().registerWorkItemHandler("Human Task", handler);
		
		return ksession;
	}

	private static KnowledgeBase readKnowledgeBase(String... files) {
		KnowledgeBuilder kbuilder = KnowledgeBuilderFactory.newKnowledgeBuilder();
		for (String file : files) {
			kbuilder.add(ResourceFactory.newClassPathResource(file), ResourceType.BPMN2);
		}
		
		return kbuilder.newKnowledgeBase();
	}

	private static StatefulKnowledgeSession getKnowledgeSession(KnowledgeBase kbase) {
		
		// This need to check if the session is already there and use the load the session instead
		// using JBPMHelper.loadStatefulKnowledgeSession(kbase, sessionId);
		StatefulKnowledgeSession ksession = kbase.newStatefulKnowledgeSession();
		return ksession;
	}


	
	
}
