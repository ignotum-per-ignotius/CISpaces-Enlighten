/******************************************************************************
 * This research was sponsored by the U.S. Army Research Laboratory and the
 * U.K. Ministry of Defence under the Biennial Program Plane 2013 (BPP13),
 * Project 6, Task 3: Collaborative Intelligence Analysis.
 * The U.S. and U.K. Governments are authorized to reproduce and distribute
 * reprints for Government purposes notwithstanding any copyright notation
 * hereon.
 * **************************************************************************
 *
 *
 *
 * @author      Alice Toniolo
 * @version     1.0
 * @since       April 2015
 *
 */

package utils;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.MutableDateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/**
 * Provenance timestamps. CISpaces stores local times as {@code yyyy-MM-dd HH:mm:ss}.
 * {@link #getDateCIS(String)} also accepts the older {@code yyyy/MM/dd HH:mm:ss} form.
 * SQL timestamps for the version-control service are a different class,
 * {@code vcservlet.TimeHelper}.
 */
public class TimeHelper {
	private static final DateTimeFormatter XML_DATE_TIME_FORMAT =
			ISODateTimeFormat.dateTimeNoMillis();
	private static final DateTimeFormatter CIS_DASH =
			DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter CIS_SLASH =
			DateTimeFormat.forPattern("yyyy/MM/dd HH:mm:ss");
	private static final DateTimeFormatter DISPLAY =
			DateTimeFormat.forPattern("dd-MM-yyyy hh:mm:ss");

	public TimeHelper() {

	}

	public String now(){
		MutableDateTime dNow = new MutableDateTime(DateTimeZone.UTC);
		return DISPLAY.print(dNow);
	}

	public String before(int days){
		MutableDateTime now = new MutableDateTime(DateTimeZone.UTC);
		DateTime daysAgo = now.toDateTime();
		daysAgo=daysAgo.minusDays(days);
		return DISPLAY.print(daysAgo);
	}

	public String parseCIS(String text){
		DateTime dt = XML_DATE_TIME_FORMAT.parseDateTime(text);
		return CIS_DASH.print(dt);
	}

	public String nowCIS(){
		MutableDateTime dNow = new MutableDateTime(DateTimeZone.UTC);
		return CIS_DASH.print(dNow);
	}

	public String getDateCIS(String text){
		DateTime dt = parseCisLocal(text);
		return DISPLAY.print(dt);
	}

	public DateTime parseDateCIS(String text){
		return XML_DATE_TIME_FORMAT.parseDateTime(text);
	}

	private static DateTime parseCisLocal(String text){
		try {
			return CIS_DASH.parseDateTime(text);
		} catch (IllegalArgumentException dash) {
			return CIS_SLASH.parseDateTime(text);
		}
	}
}
