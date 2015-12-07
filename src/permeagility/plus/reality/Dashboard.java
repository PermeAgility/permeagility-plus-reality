package permeagility.plus.reality;

import java.util.HashMap;

import permeagility.util.DatabaseConnection;
import permeagility.web.Message;
import permeagility.web.Security;
import permeagility.web.Table;

public class Dashboard extends Table {
    
	@Override
	public String getPage(DatabaseConnection con, HashMap<String, String> parms) {
	
		StringBuilder sb = new StringBuilder();
		StringBuilder errors = new StringBuilder();

		String submit = parms.get("SUBMIT");
		String view = parms.get("VIEW");
		String tableName = parms.get("TABLENAME");
		String editId = parms.get("EDIT_ID");
		String updateId = parms.get("UPDATE_ID");
		String additionalStyle = "";
				
		// Process update of work tables
		if (updateId != null && submit != null) {
			System.out.println("update_id="+updateId);
			if (submit.equals(Message.get(con.getLocale(), "DELETE"))) {
				if (deleteRow(con, tableName, parms, errors)) {
					submit = null;
				} else {
					return head("Could not delete", getDateControlScript(con.getLocale())+getColorControlScript())
							+ body(standardLayout(con, parms, getTableRowForm(con, tableName, parms) + errors.toString()));
				}
			} else if (submit.equals(Message.get(con.getLocale(), "UPDATE"))) {
				System.out.println("In updating row");
				if (updateRow(con, tableName, parms, errors)) {
				} else {
					return head("Could not update", getDateControlScript(con.getLocale())+getColorControlScript())
							+ body(standardLayout(con, parms, getTableRowForm(con, tableName, parms) + errors.toString()));
				}
			} 
			// Cancel is assumed
			editId = null;
			updateId = null;
			view = parms.get(PARM_PREFIX+"connection");
		}

		// Create a ROW directly
		if (submit != null && submit.equals(Message.get(con.getLocale(), "CREATE_ROW"))) {
			boolean inserted = insertRow(con,tableName,parms,errors);
			if (!inserted) {
				errors.append(paragraph("error","Could not insert"));
			}
		}
		
		// Show edit form if row selected for edit
		if (editId != null && submit == null && view == null) {
			return head("Edit", getScripts(con))
					+ body(standardLayout(con, parms, getTableRowForm(con, tableName, parms)));
		}
		
		if (sb.length() == 0) {
	    	try {
	    		parms.put("SERVICE", "View: Setup view");
				sb.append(paragraph("banner","Select View"));
				sb.append(getTable(con,parms,PlusSetup.TABLE_DEVICE,"SELECT FROM "+PlusSetup.TABLE_DEVICE, null,0, "name, description, button(VIEW:View),-"));
	    	} catch (Exception e) {  
	    		e.printStackTrace();
	    		sb.append("Error retrieving import patterns: "+e.getMessage());
	    	}
		}
		return 	head("Reality Dashboard",getScripts(con)+additionalStyle)
				+body(standardLayout(con, parms, 
					((Security.getTablePriv(con, PlusSetup.TABLE_DEVICE) & PRIV_CREATE) > 0 && view == null 
						? popupForm("CREATE_NEW_ROW",null,Message.get(con.getLocale(),"CREATE_ROW"),null,"NAME",
							paragraph("banner",Message.get(con.getLocale(), "CREATE_ROW"))
							+hidden("TABLENAME", PlusSetup.TABLE_DEVICE)
							+getTableRowFields(con, PlusSetup.TABLE_DEVICE, parms)
							+submitButton(con.getLocale(), "CREATE_ROW")) 
						: "")
					+errors.toString()
					+sb.toString()
				));
	}
	
}

