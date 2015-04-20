package permeagility.plus.reality;

import java.util.HashMap;

import permeagility.util.DatabaseConnection;
import permeagility.util.Setup;
import permeagility.web.Server;

import com.orientechnologies.orient.core.metadata.schema.OClass;
import com.orientechnologies.orient.core.metadata.schema.OSchema;
import com.orientechnologies.orient.core.metadata.schema.OType;

public class PlusSetup extends permeagility.plus.PlusSetup {

	public static boolean INSTALLED = false;  // Set via constant to complete installation
	public static String INSTALLED_VERSION = "0";  // Set via constant to complete installation
	
	// Override these to change the names of the tables that will be created and used by this importer
	public static String TABLE_AREA = "area";   
	public static String TABLE_LOCATION = "location";   
	public static String TABLE_HOST = "host";   
	public static String TABLE_DEVICE = "device";   
	public static String TABLE_TAG = "tag";   
	public static String TABLE_SCHEDULE = "schedule";   
	public static String TABLE_TASK = "task";   
	public static String TABLE_TYPE = "type";   
	public static String TABLE_UNIT = "unit";   
	
	public static String MENU_CLASS = "permeagility.plus.reality.Dashboard";
	
	public String getName() { return "Reality"; }
	public String getInfo() { return "Connect PermeAgility to the Real World for monitoring and control"; }
	public String getVersion() { return "0.1.0"; }
	
	public boolean isInstalled() { return INSTALLED; }
	
	public boolean install(DatabaseConnection con, HashMap<String,String> parms, StringBuilder errors) {
		OSchema oschema = con.getSchema();
		String newTableGroup = pickTableGroup(con, parms);
				
		if (isNullOrBlank(newTableGroup) || isNullOrBlank(parms.get("MENU")) || isNullOrBlank(parms.get("ROLES"))) {
			errors.append(paragraph("error","Please specify a table group, menu and the roles to allow access"));
			return false;
		}
		OClass ofunction = oschema.getClass("OFunction");
		if (ofunction == null) {
			errors.append(paragraph("error","Could not find OFunction table for links, aborting"));
			return false;
		}
		
		OClass tableArea = Setup.checkCreateTable(con, oschema, TABLE_AREA, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableArea, "name", OType.STRING, errors);

		OClass tableUnit = Setup.checkCreateTable(con, oschema, TABLE_UNIT, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableUnit, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableUnit, "alternateUnits", OType.LINKMAP, ofunction, errors);

		OClass tableType = Setup.checkCreateTable(con, oschema, TABLE_TYPE, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableType, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableType, "unit", OType.LINK, tableUnit, errors);
		
		OClass tableLocation = Setup.checkCreateTable(con, oschema, TABLE_LOCATION, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableLocation, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableLocation, "area", OType.LINK, tableArea, errors);
		Setup.checkCreateColumn(con,tableLocation, "currentlyAtLocation", OType.LINK, tableLocation, errors);

		OClass tableHost = Setup.checkCreateTable(con, oschema, TABLE_HOST, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableHost, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableHost, "url", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableHost, "description", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableHost, "location", OType.LINK, tableLocation, errors);

		OClass tableDevice = Setup.checkCreateTable(con, oschema, TABLE_DEVICE, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableDevice, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableDevice, "readFunction", OType.LINK, ofunction, errors);		
		Setup.checkCreateColumn(con,tableDevice, "description", OType.STRING, errors);

		OClass tableTag = Setup.checkCreateTable(con, oschema, TABLE_TAG, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableTag, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableTag, "host", OType.LINK, tableHost, errors);
		Setup.checkCreateColumn(con,tableTag, "device", OType.LINK, tableDevice, errors);
		Setup.checkCreateColumn(con,tableTag, "type", OType.LINK, tableType, errors);
		Setup.checkCreateColumn(con,tableTag, "identifier", OType.STRING, errors);
		
		OClass tableTask = Setup.checkCreateTable(con, oschema, TABLE_TASK, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableTask, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableTask, "readTags", OType.LINKMAP, tableTag, errors);
		Setup.checkCreateColumn(con,tableTask, "logicScript", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableTask, "writeTags", OType.LINKMAP, tableTag, errors);

		OClass tableSchedule = Setup.checkCreateTable(con, oschema, TABLE_SCHEDULE, errors, newTableGroup);
		Setup.checkCreateColumn(con,tableSchedule, "name", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableSchedule, "rule", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableSchedule, "timeout", OType.LONG, errors);
		Setup.checkCreateColumn(con,tableSchedule, "tasks", OType.LINKLIST, tableTask, errors);
		Setup.checkCreateColumn(con,tableSchedule, "message", OType.STRING, errors);
		Setup.checkCreateColumn(con,tableSchedule, "lastFinishTime", OType.DATETIME, errors);
		Setup.checkCreateColumn(con,tableSchedule, "lastDuration", OType.LONG, errors);

		Setup.createMenuItem(con,getName(),getInfo(),MENU_CLASS,parms.get("MENU"),parms.get("ROLES"));	
		
		setPlusInstalled(con, this.getClass().getName(), getInfo(), getVersion());
		INSTALLED = true;
		return true;
	}
	
	public boolean remove(DatabaseConnection con, HashMap<String,String> parms, StringBuilder errors) {

		if (parms.get("REMOVE_MENU") != null) {
			Setup.removeMenuItem(con, MENU_CLASS, errors);
		}
		
		String remTab = parms.get("REMOVE_TABLES");
		if (remTab != null && remTab.equals("on")) {
			Setup.dropTable(con, TABLE_AREA, errors);
			Setup.dropTable(con, TABLE_LOCATION, errors);
			Setup.dropTable(con, TABLE_HOST, errors);
			Setup.dropTable(con, TABLE_DEVICE, errors);
			Setup.dropTable(con, TABLE_TAG, errors);
			Setup.dropTable(con, TABLE_SCHEDULE, errors);
			Setup.dropTable(con, TABLE_TASK, errors);
			Setup.dropTable(con, TABLE_TYPE, errors);
			Setup.dropTable(con, TABLE_UNIT, errors);
		}

		setPlusUninstalled(con, this.getClass().getName());
		INSTALLED = false;
		return true;
	}
	
	public boolean upgrade(DatabaseConnection con, HashMap<String,String> parms, StringBuilder errors) {
		// Perform upgrade actions

		setPlusVersion(con,this.getClass().getName(),getInfo(),getVersion());
		return true;
	}

}
