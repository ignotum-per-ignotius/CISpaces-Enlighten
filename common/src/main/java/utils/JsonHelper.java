/******************************************************************************
 * This research was sponsored by the U.S. Army Research Laboratory and the
 * U.K. Ministry of Defence under the Biennial Program Plane 2013 (BPP13),
 * Project 6, Task 3: Collaborative Intelligence Analysis.
 * The U.S. and U.K. Governments are authorized to reproduce and distribute
 * reprints for Government purposes notwithstanding any copyright notation
 * hereon.
 * **************************************************************************
 *
 * This is a JSON converter to HashMaps
 *
 *
 * @author      Alice Toniolo
 * @version     1.0
 * @since       April 2014
 *
 */

package utils;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.LinkedTreeMap;

public class JsonHelper {
	private static Logger log;
	private final boolean prettyPrint;
	private final boolean serializeNulls;

	/** Compact JSON, nulls omitted. Matches VC, PROVSIMP, and setProvenance. */
	public JsonHelper(){
		this(false, false);
	}

	/**
	 * @param prettyPrint info and provgraphs pretty-print HashMap JSON
	 * @param serializeNulls ers keeps null map entries in HashMap JSON
	 */
	public JsonHelper(boolean prettyPrint, boolean serializeNulls){
		 log=Logger.getLogger(getClass().getName());
		 this.prettyPrint = prettyPrint;
		 this.serializeNulls = serializeNulls;
	}


	public HashMap convertInputMap(String json) {
		 HashMap map=new HashMap();
		 Gson gson = new Gson();
	 try{
		 map = gson.fromJson(json, HashMap.class);
	 }catch(Exception e){
		 log.log(Level.SEVERE,"Exception Not a JSON string");
	 }
		return  map;
	}

	public LinkedTreeMap convertInputMapG(String json) {
		LinkedTreeMap map=new LinkedTreeMap();
		 Gson gson = new Gson();
	try{
		 map = gson.fromJson(json, LinkedTreeMap.class);
	}catch(Exception e){
		 log.log(Level.SEVERE,"Exception Not a JSON string");
	}
		return  map;
	}

	public String convertInputJson(HashMap input) {
			GsonBuilder builder = new GsonBuilder()
				.disableHtmlEscaping();
			if(prettyPrint){
				builder.setPrettyPrinting();
			}
			if(serializeNulls){
				builder.serializeNulls();
			}
			String response=builder.create().toJson(input);
			return response;
		}

	public String convertInputJson(LinkedTreeMap input) {
		Gson gson = new GsonBuilder()
			.disableHtmlEscaping()
			.create();
		String response=gson.toJson(input);
		return response;
	}


}
