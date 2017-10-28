/* 
 * Copyright 2015 PermeAgility Incorporated.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package permeagility.plus.reality;

import java.util.HashMap;

import permeagility.util.DatabaseConnection;
import permeagility.util.QueryResult;
import permeagility.web.Message;
import permeagility.web.Security;
import permeagility.web.Table;

import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OProperty;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocument;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;
import permeagility.plus.json.JSONObject;
import permeagility.util.Database;
import permeagility.util.Setup;
import permeagility.web.Server;

public class MQTTSubscribe extends Table {

    // Override this with a constant to true after installation to avoid installation check
    public static boolean INSTALLED = false;  // Will check for existence of config tables and create - can turn off in constant

    public static boolean DEBUG = true;
    
    public static boolean START = false;  // Set this via constant to true to auto-start subscriptions when system starts
    
    private static MQTTSubscribe instance = null;

    private static Timer heartbeat = null;
    private static int HEARTBEAT_DELAY = 1000; // til furst run
    private static int HEARTBEAT_DURATION = 10000;  // in milliseconds

    public static int DEFAULT_TIMEOUT = 120000;  // in ms
    public static boolean HARD_TIMEOUT = true;

    public static enum JobState {
        SETUP, FINISHED, RUNNING, WAITING, FAILED, CANCEL, TIMEOUT
    };

    private static ConcurrentHashMap<String, Subscriber> jobs = new ConcurrentHashMap<>();
    private static Database db = null;
    private static ExecutorService executor = Executors.newCachedThreadPool();

        public MQTTSubscribe() {
        if (instance == null) {
            instance = this;
            if (DEBUG) {
                System.out.println("Created MQTT Subscription process runner");
            }
            heartbeat = new Timer();
            heartbeat.schedule(new TimerTask() {
                long lastRun = 0;

                public void run() {  // Will check at every heartbeat and will run any enabled subscriptions not already running
                    if (START) {
                        if (lastRun == 0) {
                            lastRun = System.currentTimeMillis();  // need this???
                        }
                        long startTime = System.currentTimeMillis();                   
                        if (db == null) {
                            String u = "admin", p = "admin";
                            try {
                                db = new Database(Server.getDBName(), u, p);
                            } catch (Exception e) {
                                System.out.println("Reality MQTT Subscriber could not log in as " + u + ": " + e.getMessage());
                                db = null;
                            }
                        }
                        if (db != null) {
                            DatabaseConnection con = db.getConnection();
                            if (con != null) {
                                QueryResult scheduleJobs = con.query("SELECT FROM " + PlusSetup.TABLE_MQTT_SUB+ " WHERE enable=true");
                                for (ODocument job : scheduleJobs.get()) {
                                    String key = job.getIdentity().toString().substring(1);
                                    if (jobs.containsKey(key) && (jobs.get(key).status == JobState.FAILED || jobs.get(key).status == JobState.FINISHED || jobs.get(key).status == JobState.TIMEOUT)) {  
                                       System.out.println("Removing failed/finished/timeout MQTT subscription job "+key);
                                       jobs.remove(key);  // If failed, get it out of the way
                                    }
                                    if (!jobs.containsKey(key)) {
                                        System.out.println("Auto-Starting MQTT Subscription "+job.field("name"));
                                        runSubscription(con, job);
                                    }
                                }
                            }
                            db.freeConnection(con);
                        }
                        if (DEBUG) {
                            System.out.println("  beat: " + (System.currentTimeMillis() - startTime) + "ms (" + db.getActiveCount() + ")");
                        }
                        lastRun = System.currentTimeMillis();
                    }
                }
            }, HEARTBEAT_DELAY, HEARTBEAT_DURATION);  // First and next occurrence
        }
    }

    private void runSubscription(DatabaseConnection con, ODocument mDoc) {
        Subscriber newSub = new Subscriber();
        newSub.details = mDoc;
        newSub.con = con;
        newSub.timeout = 120000;
        jobs.put(mDoc.getIdentity().toString().substring(1), newSub);
        newSub.future = executor.submit(newSub);
    }    
       
    @Override
    public String getPage(DatabaseConnection con, HashMap<String, String> parms) {

        StringBuilder sb = new StringBuilder();
        StringBuilder errors = new StringBuilder();

        String run = parms.get("RUN");

        // Process update of work tables
        String update = processSubmit(con, parms, PlusSetup.TABLE_MQTT_SUB, errors);
        if (update != null) { return update; }

        if (run != null) {
            // Run a mqtt topic receiver 
            ODocument mDoc = con.get(run);
            if (mDoc != null) {
                if (jobs.get(run) == null || jobs.get(run).status == JobState.FINISHED || jobs.get(run).status == JobState.FAILED  || jobs.get(run).status == JobState.TIMEOUT) {
                    System.out.println("Running subscription: " + mDoc.field("name"));
                    runSubscription(con, mDoc);
                } else {
                    errors.append(paragraph("error", "Already running"));
                }
                
            }
        }
        if (sb.length() == 0) {
            try {
                parms.put("SERVICE", "MQTT Subscriptions");
                sb.append(paragraph("banner", "Currently running processes"));
                for (Subscriber sub : jobs.values()) {
                    ODocument details = con.getDb().reload(sub.details);
                    //ODocument host = details.field("host");
                    sb.append(paragraph(details.field("name")+" is "+sub.status.name()));
                }

                sb.append(paragraph("banner", "Configured topics"));
                sb.append(getTable(con, parms, PlusSetup.TABLE_MQTT_SUB, "SELECT FROM " + PlusSetup.TABLE_MQTT_SUB, null, 0, "button_RUN_Run, name, host, topic, pattern, tableName, columnName"));
            } catch (Exception e) {
                e.printStackTrace();
                sb.append("Error retrieving topics: " + e.getMessage());
            }
        }
        return head("MQTT Subs", getDateControlScript(con.getLocale()) + getColorControlScript())
                + body(standardLayout(con, parms,
                                errors.toString()
                                + ((Security.getTablePriv(con, PlusSetup.TABLE_MQTT_SUB) & PRIV_CREATE) > 0
                                        ? popupForm("CREATE_NEW_ROW", this.getClass().getName(), "New subscription", null, "name",
                                                paragraph("banner", Message.get(con.getLocale(), "CREATE_ROW"))
                                                + hidden("TABLENAME", PlusSetup.TABLE_MQTT_SUB)
                                                + getTableRowFields(con, PlusSetup.TABLE_MQTT_SUB, parms, "name, host, topic, pattern, tableName, columnName")
                                                + submitButton(con.getLocale(), "CREATE_ROW"))
                                        : "")
                                + sb.toString()
                        ));
    }

        
       private class Subscriber implements Runnable {

        // These will be given

        public long timeout;
        public ODocument details;  // The mqttSubs record
        public DatabaseConnection con;

        // These will be maintained by the job
        public JobState status = JobState.WAITING;
        public long startTime = System.currentTimeMillis();
        public long endTime;
        public String message;
        public Future<?> future;  // Return object - for cancelling
        //private int recordVersion;

        public void run() {
            startTime = System.currentTimeMillis();
            status = JobState.SETUP;
            String currentTask = null;
            try {
                con.getDb().reload(details);
                status = JobState.RUNNING;
                executeTask(this);
                message = "OK";
                status = JobState.FINISHED;
            } catch (InterruptedException ie) {
                status = JobState.TIMEOUT;
                message = "Timeout after " + (System.currentTimeMillis() - startTime) + "ms in task " + currentTask;
                if (DEBUG) {
                    System.out.println(details.field("name") + " task(" + currentTask + ") got Interrupted:" + ie.getMessage() + " after " + (System.currentTimeMillis() - startTime) + "ms");
                }
            } catch (Exception e) {
                status = JobState.FAILED;
                message = "Failed in task " + currentTask + ": " + e.toString();
                System.out.print("JobException:");
                e.printStackTrace();
            } finally {
                endTime = System.currentTimeMillis();
                con.getDb().reload(details);
                details.field("message", message);
                details.save();
            }
            if (DEBUG) {
                System.out.println("Finished " + details.field("name") + " in " + (endTime - startTime) + "ms " + status);
            }
        }
    }

    private static void executeTask(Subscriber subscriber) throws InterruptedException, Exception {
        DatabaseConnection con = subscriber.con;
        BlockingConnection connection = null;
        ODocument task = subscriber.details;
        String topic = task.field("topic");
        ODocument host = task.field("host");
        String url = host.field(("url"));

        try {
            System.out.println("Subscribing to "+topic+" on "+url);
            MQTT mqtt = new MQTT();
            mqtt.setHost(url.startsWith("tcp://") ? url : "tcp://"+url);
            connection = mqtt.blockingConnection();
            connection.connect();
            Topic[] topics = { new Topic(topic,QoS.AT_LEAST_ONCE) };
            connection.subscribe(topics);
            while (connection.isConnected() && (boolean)subscriber.details.field("enable") == true) {                
                subscriber.status = JobState.WAITING;
                org.fusesource.mqtt.client.Message mess = connection.receive();
                subscriber.status = JobState.RUNNING;
                String payload = mess.getPayloadBuffer().toString();
                String t = mess.getTopic();
                if (payload.startsWith("ascii: ")) {
                    payload = payload.substring(7);
                }
                if (DEBUG) System.out.println(task.field("name")+" received "+host.field("name")+" "+t+": "+payload);
                try {

                    // Extract pattern vars from incoming topic
                    HashMap<String,String> vars = new HashMap();
                    String pattern = task.field("pattern");
                    String table = task.field("tableName");
                    String column = task.field("columnName");
                    String[] tPart = t.split("/");
                    String[] pPart = pattern.split("/");
                    for (int i=0;i<pPart.length;i++) {
                        if (i<tPart.length && tPart[i].equals(pPart[i])) {
                            System.out.println("Match");
                        } else {
                            if (pPart[i].startsWith("(") && pPart[i].endsWith(")")) {
                                // create a var
                                String vname = pPart[i].substring(1,pPart[i].length()-1);
                                vars.put(vname, tPart[i]);
                                System.out.println("Match "+vname+"="+tPart[i]);
                            }
                        }
                    }

                    // determine data type
                    boolean isNum = false;
                    boolean isJson = false;
                    JSONObject plob = null;

                    if (payload.trim().startsWith("{")) {
                        plob = new JSONObject(payload);
                        if (plob != null) {
                            isJson = true;
                        }   else {
                            System.out.println("Could not parse \""+payload+"\" as JSON even though it begins with \"{\"");
                        }                 
                    }

                    double payloadnum = 0;
                    try {
                        payloadnum = Double.parseDouble(payload);
                        isNum = true;
                    } catch (Exception e) {
                        isNum = false;
                    }
                    // Check the specified table/column for existence               
                    if (table.startsWith("(") && table.endsWith(")")) {
                        String vname = table.substring(1,table.length()-1);
                        if (vars.get(vname) != null) {
                            table = vars.get(vname);
                        } else {
                            System.out.println("Could not translate table: "+vname+" not found or defined");
                        }
                    }
                    table = makePrettyCamelCase(table);
                    OSchema schema = con.getSchema();
                    StringBuilder errors = new StringBuilder();
                    OClass tab = Setup.checkCreateTable(con, schema, table, errors, "reality");
                    OProperty tscol = Setup.checkCreateColumn(con, tab, "time", OType.DATETIME, errors);  // need a timestamp column

                    if (column != null && column.startsWith("(") && column.endsWith(")")) {
                        String vname = column.substring(1,column.length()-1);
                        if (vars.get(vname) != null) {
                            column = vars.get(vname);
                        }
                    }
                    if (isJson) { 
                        ODocument newdata = con.create(table);
                        newdata.field("time",new Date());   // set the time
                        for (String cn : plob.keySet()) {   // and push each property into a column
                            String acolname = (column==null?"":column) + makePrettyCamelCase(cn);
                            Object colVal = plob.get(cn);
                            if (colVal instanceof Number) {
                                isNum = true;
                                OProperty col = Setup.checkCreateColumn(con, tab, acolname, OType.DOUBLE, errors);
                                newdata.field(acolname,((Number) colVal).doubleValue());
                            } else {
                                OProperty col = Setup.checkCreateColumn(con, tab, acolname, OType.STRING, errors);                            
                                newdata.field(acolname,colVal.toString());
                            }
                        }
                        newdata.save();
                    } else {  // single value, column must have a name
                        if (column != null && !column.isEmpty()) {
                            column = makePrettyCamelCase(column);
                            OProperty col = Setup.checkCreateColumn(con, tab, column, isNum ? OType.DOUBLE : OType.STRING, errors);

                            // Push the data into the table/column(s)
                            ODocument newdata = con.create(table);
                            newdata.field("time",new Date());
                            newdata.field(column, isNum ? payloadnum : payload);
                            newdata.save();
                        } else {
                            System.out.println("Need a column name for a single value");
                        }
                    }

                } catch (Exception e) {
                  System.out.println("Error in subscription message receipt of " + task.field("name")+ " message=" + e.getMessage());                    
                  e.printStackTrace();
                }
                // Ackowledge receipt of message
                mess.ack();

                // refresh the task to see if still enabled
                con.getDb().reload(subscriber.details);

            }
            
        } catch (Exception e) {
            throw new InterruptedException("Interrupted subscription " + task.field("name")+ " message=" + e.getMessage());
        } finally {
            try {
                if (connection != null && connection.isConnected()) {
                    connection.disconnect();
                }
            } catch (Exception e) {
                System.out.println("failed to disconnect - boo hoo");
            }
        }
    }
}
