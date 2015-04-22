package permeagility.plus.reality;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import permeagility.util.Database;
import permeagility.util.DatabaseConnection;
import permeagility.util.QueryResult;
import permeagility.web.Server;

import com.orientechnologies.orient.core.record.impl.ODocument;

public class Scheduler {

	public static boolean START = false;  // Set this via constant to true to start scheduler when system starts
	private static Scheduler instance = null;
	public static boolean DEBUG = true;
	
	private static boolean INITIALIZED = false;
    private static Timer heartbeat = null;
    private static int HEARTBEAT_DELAY = 1000;
    private static int HEARTBEAT_DURATION = 1000;  // in milliseconds
    
    public static int DEFAULT_TIMEOUT = 5000;
    public static boolean HARD_TIMEOUT = true;
    
	private static String SETTINGS_FILE = "initReality.pa";
	private static Properties localSettings = new Properties();
	
	public static enum JobState { FINISHED, RUNNING, FAILED, CANCEL, TIMEOUT };
	
	private static ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<String, Job>();
	private static Database db = null;
	private static ExecutorService executor = Executors.newCachedThreadPool();
	
	public Scheduler() {
		if (instance == null) {
			instance = this;
			if (DEBUG) System.out.println("Created instance of Scheduler");
            heartbeat = new Timer();
            heartbeat.schedule(new TimerTask() {
            	long lastRun = 0;
                public void run() {
                    if (START) {
                    	if (lastRun == 0) {
                    		lastRun = System.currentTimeMillis();  // need this???
                    	}
                    	long startTime = System.currentTimeMillis();
                    	if (db == null) {
	                    	String u,p = null;
	                    	u = getLocalSetting("u", null);
	                    	if (u != null) p = getLocalSetting("p", null);
	                    	if (u != null || p != null) {
	                    		try {
									db = new Database(Server.getDBName(),u,p);
								} catch (Exception e) {
									System.out.println("Reality Scheduler could not log in as "+u+": "+e.getMessage());
									db = null;
								}
	                    	}
                    	}
                    	if (db != null) {
                    		DatabaseConnection con = db.getConnection();
                    		if (con != null) {
                    			Date now = new Date();
                    			if (HARD_TIMEOUT) {
                    				for (Job j : jobs.values()) {
            							if (j.status == JobState.RUNNING && j.startTime + j.timeout < now.getTime()) {
            								j.future.cancel(true);
            								j.status = JobState.CANCEL;
            							}                    					
                    				}
                    			}
                    			QueryResult scheduleJobs = con.query("SELECT FROM "+PlusSetup.TABLE_SCHEDULE);
                    			for (ODocument job : scheduleJobs.get()) {
                    				//System.out.println("Job is "+job.field("name"));
                    				String cronRule = job.field("rule");
                    				String id = job.getIdentity().toString().substring(1);
                    				try {
                    					CronExpression ce = new CronExpression(cronRule);
                    					if (ce.isSatisfiedBy(now)) {
                    						Job j = jobs.get(id);
                    						if (j != null && j.status == JobState.RUNNING) {
                    							if (j.startTime + j.timeout < now.getTime()) {
                    								j.future.cancel(true);
                    								j.status = JobState.CANCEL;
                    							}
                    						}
                    						if (j == null || j.status == JobState.CANCEL) {
                    							j = new Job();
                    						}
                    						if (j.status != JobState.RUNNING) {
                        						j.details = job;
                        						j.timeout = (job.field("timeout") != null ? job.field("timeout") : DEFAULT_TIMEOUT);
                        						j.con = db.getConnection();  // Will be freed when job finishes
                        						jobs.put(id,j);
                        						System.out.print("  Running "+job.field("name")+" status="+j.status);
                        						j.future = executor.submit(j);
                							} else {
                								System.out.println("Job is already running");
                							}
                    					}
                    				} catch (ParseException e) {
                    					job.field("message","Could not parse rule '"+cronRule+"', "+e.getMessage()).save();
                    				}
                    			}
                    		}
                    		db.freeConnection(con);
                    	}
                    	if (DEBUG) System.out.println("  beat: "+(System.currentTimeMillis()-startTime)+"ms ("+db.getActiveCount()+")");
                		lastRun = System.currentTimeMillis();
                    }
                }}, HEARTBEAT_DELAY, HEARTBEAT_DURATION);  // First and next occurrence
		}
	}

	private class Job implements Runnable {
		// These will be given
		public long timeout;
		public ODocument details;  // The schedule record with tasks, etc...
		public DatabaseConnection con;
		
		// These will be maintained by the job
		public JobState status;
		public long startTime;
		public long endTime;
		public String message;
		public Future<?> future;  // Return object - for cancelling
		private int recordVersion;
	    public void run() {
	    	startTime = System.currentTimeMillis();
	    	status = JobState.RUNNING;
	    	String currentTask = null;
	    	try {
				con.getDb().reload(details);
				recordVersion = details.getVersion();
	    		List<ODocument> tasks = details.field("tasks");
	    		for (ODocument task : tasks) {
	    			currentTask = task.field("name");
	    			executeTask(task);
	    		}
	    		message = "OK";
		    	status = JobState.FINISHED;
	    	} catch (InterruptedException ie) {
	    		status = JobState.TIMEOUT;
	    		message = "Timeout after "+(System.currentTimeMillis()-startTime)+"ms in task "+currentTask;
	    		if (DEBUG) System.out.println(details.field("name")+" task("+currentTask+") got Interrupted:"+ie.getMessage()+" after "+(System.currentTimeMillis()-startTime)+"ms");
	    	} catch (Exception e) {
	    		status = JobState.FAILED;
	    		message = "Failed in task "+currentTask+": "+e.toString();
	    		System.out.print("JobException:");
	    		e.printStackTrace();
	    	} finally {
		    	endTime = System.currentTimeMillis();
	    		if (con != null) {
	    			try {
	    				con.getDb().getLocalCache().invalidate();
	    				con.getDb().reload(details);
	    				details.field("message",message)
	    				.field("lastFinishTime",new Date(endTime))
	    				.field("lastDuration",endTime-startTime)
	    				.save();
	    			} catch (Exception e) {
	    				System.out.println("Could not save job finish details of "+details.field("name")+":"+e.getMessage());
	    			}
	    			db.freeConnection(con);
	    		}
	    	}
    		if (DEBUG) System.out.println("Finished "+details.field("name")+" in "+(endTime-startTime)+"ms "+status);
	    }
	}
	
	private static void executeTask(ODocument task) throws InterruptedException {
		String logicScript = task.field("logicScript");
		ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
		try {
			engine.eval("var taskFunction = function(object) {\n"+logicScript+"\n};");
			Invocable invocable = (Invocable)engine;
			Object result = invocable.invokeFunction("taskFunction", task);
			System.out.println(result);
		} catch (Exception e) {
			throw new InterruptedException("Error in script "+logicScript+" message="+e.getMessage());
		}
	}
	
	public static void setLocalSetting(String key, String value) {
		if (value == null) {
			localSettings.remove(key);
		} else {
			localSettings.put(key,value);
		}
		try {
			localSettings.store(new FileWriter(SETTINGS_FILE), "Initialization Parameters for PermeAgility Reality Scheduler");
		} catch (IOException e) {
			System.out.println("Cannot store init file");
		}
	}
	
	private static String getLocalSetting(String key, String def) {
		try {
			localSettings.load(new FileReader(SETTINGS_FILE));
		} catch (IOException fnf) { 
			System.out.println("Cannot open init file for Reality Scheduler (initReality.pa) - assuming defaults");
		}
		return localSettings.getProperty(key,def);	
	}
	
}
