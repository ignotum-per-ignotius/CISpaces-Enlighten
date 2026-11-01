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
 * @since       September 2014
 *
 */

package utils;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Iterator;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/**
 * One provenance pattern builder for both bundled {@code prov.ProvenanceBundle}
 * jars. PROVSIMP loads the Jena 3 bundle; info and setProvenance load the Jena 2
 * bundle. The two jars are not binary-compatible, so this class talks to
 * whichever bundle is on the classpath instead of importing a Jena package.
 * The method set is the setProvenance copy, including the analysis patterns.
 */
@SuppressWarnings("rawtypes")
public class PatternBuilder {

	private final Object bundle;
	private final Object prov;

	public PatternBuilder() {
		try {
			Class<?> type = Class.forName("prov.ProvenanceBundle");
			bundle = type.getDeclaredConstructor().newInstance();
			Field modelField = type.getDeclaredField("prov");
			modelField.setAccessible(true);
			prov = modelField.get(bundle);
			Field rdfNs = type.getDeclaredField("RDF_NS");
			rdfNs.setAccessible(true);
			String rdf = (String) rdfNs.get(null);
			call(prov, "setNsPrefix", "foaf", NS.FOAF);
			call(prov, "setNsPrefix", "cisp", NS.CISP);
			call(prov, "setNsPrefix", "prov", NS.PROV);
			call(prov, "setNsPrefix", "rdf", rdf);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Could not construct prov.ProvenanceBundle", e);
		}
	}

	public void setModel(Object mod) {
		call(prov, "add", mod);
	}

	@SuppressWarnings("unchecked")
	public <T> T getModel() {
		return (T) call(bundle, "getModel");
	}

	public void write(OutputStream out) {
		call(bundle, "write", out);
	}

	public void makeGenerationPattern(Object a1, Object a2, String p1, Object ag1, String time) {
		Object aa1 = getCISEntity(a1);
		Object aa2 = getCISEntity(a2);
		Object agg1 = getCISAgent(ag1);
		createActivity(p1);
		Object pp1 = resource(NS.URICISP + p1);
		relate("addWasDerivedFrom", aa1, aa2);
		relate("addUsed", pp1, aa2);
		addWasGeneratedBy(aa1, pp1, time);
		relate("addWasAssociatedWith", pp1, agg1);
	}

	public void makeAnalysisGenerationPattern(String location, String anname, String ayid, String prevname, ArrayList users, String p1, String time, ArrayList ids) {
		Object an1 = makeAnalysis(anname, ayid, location);
		createEntity(prevname);
		Object an2 = resource(NS.URICISP + prevname);
		relate("addWasDerivedFrom", an1, an2);
		createActivity(p1);
		Object pp1 = resource(NS.URICISP + p1);
		addWasGeneratedBy(an1, pp1, time);
		Iterator iter = users.iterator();
		while (iter.hasNext()) {
			relate("addWasAssociatedWith", pp1, getCISAgent(iter.next()));
		}
		iter = ids.iterator();
		while (iter.hasNext()) {
			String nd = "Node" + (String) iter.next();
			relate("addUsed", pp1, getCISEntity(nd));
		}
		relate("addUsed", pp1, an2);
	}

	public void addUsersToGeneration(String activity, ArrayList users) {
		createActivity(activity);
		Object pp1 = resource(NS.URICISP + activity);
		Iterator iter = users.iterator();
		while (iter.hasNext()) {
			relate("addWasAssociatedWith", pp1, getCISAgent(iter.next()));
		}
	}

	public void addNodeWasDerivedFromAnalysis(String location, String nodename, String analyname, String analy) {
		createEntity(nodename);
		Object en = resource(NS.URICISP + nodename);
		makeAnalysis(analyname, analy, location);
		Object wp = resource(NS.URICISP + analyname);
		relate("addWasDerivedFrom", wp, en);
	}

	public void addLocationToAnalysis(String anID, String analyname, String location) {
		makeAnalysis(analyname, anID, location);
	}

	public void makeAnalywasDerived(String en1, String en2) {
		relate("addWasDerivedFrom", getCISEntity(en1), getCISEntity(en2));
	}

	public void makeSimpleAnalysisGenerationPattern(String location, String analy, String ayid, ArrayList users, String p1, String time, ArrayList ids) {
		Object an1 = makeAnalysis(analy, ayid, location);
		createActivity(p1);
		Object pp1 = resource(NS.URICISP + p1);
		addWasGeneratedBy(an1, pp1, time);
		Iterator iter = users.iterator();
		while (iter.hasNext()) {
			relate("addWasAssociatedWith", pp1, getCISAgent(iter.next()));
		}
		iter = ids.iterator();
		while (iter.hasNext()) {
			relate("addUsed", pp1, getCISEntity(iter.next()));
		}
	}

	public void makeSimpleGenerationPattern(Object a1, String p1, Object ag1, String time) {
		Object aa1 = getCISEntity(a1);
		Object agg1 = getCISAgent(ag1);
		createActivity(p1);
		Object pp1 = resource(NS.URICISP + p1);
		addWasGeneratedBy(aa1, pp1, time);
		relate("addWasAssociatedWith", pp1, agg1);
	}

	public void makeCSRequPattern(Object a1, Object a2, Object a3, String p1, String p2, String t_a1p1, String t_a2p2, Object ag1, Object ag2) {
		Object aa1 = getCISEntity(a1);
		Object aa2 = getCISEntity(a2);
		Object aa3 = getCISEntity(a3);
		Object agg1 = getCISAgent(ag1);
		Object agg2 = getCISAgent(ag2);
		createActivity(p1);
		createActivity(p2);
		Object pp1 = resource(NS.URICISP + p1);
		Object pp2 = resource(NS.URICISP + p2);
		relate("addWasInformedBy", pp1, pp2);
		relate("addWasAssociatedWith", pp1, agg1);
		relate("addWasAssociatedWith", pp2, agg2);
		addWasGeneratedBy(aa1, pp1, t_a1p1);
		addWasGeneratedBy(aa2, pp2, t_a2p2);
		relate("addUsed", pp1, aa2);
		relate("addUsed", pp1, aa3);
		relate("addUsed", pp2, aa3);
		relate("addWasDerivedFrom", aa1, aa2);
	}

	public void makeRequirementPattern(Object a1, Object a2, String g1, String p1, String p2, String t_a1p1, String t_a2p2) {
		Object aa1 = getCISEntity(a1);
		Object aa2 = getCISEntity(a2);
		Object gg1 = makeGoal(g1);
		createActivity(p1);
		createActivity(p2);
		Object pp1 = resource(NS.URICISP + p1);
		Object pp2 = resource(NS.URICISP + p2);
		relate("addWasInformedBy", pp1, pp2);
		addWasGeneratedBy(aa1, pp1, t_a1p1);
		addWasGeneratedBy(aa2, pp2, t_a2p2);
		relate("addUsed", pp1, aa2);
		relate("addUsed", pp2, gg1);
	}

	public void addNodesToGeneration(String activity, ArrayList nodes) {
		createActivity(activity);
		Object pp1 = resource(NS.URICISP + activity);
		Iterator iter = nodes.iterator();
		while (iter.hasNext()) {
			String nd = "Node" + (String) iter.next();
			relate("addUsed", pp1, getCISEntity(nd));
		}
	}

	public void makePrimarySourcePattern(ArrayList ps, String p1, Object ag1, Object a1, String t_a1p1) {
		createActivity(p1);
		Object aa1 = getCISEntity(a1);
		Object pp1 = resource(NS.URICISP + p1);
		addWasGeneratedBy(aa1, pp1, t_a1p1);
		relate("addWasAssociatedWith", pp1, getCISAgent(ag1));
		Iterator iter = ps.iterator();
		while (iter.hasNext()) {
			Object pas = makePrimarySource(getCISEntity(iter.next()));
			relate("addUsed", pp1, pas);
		}
	}

	private Object getCISAgent(Object ag) {
		if (ag instanceof String) {
			String art = (String) ag;
			createAgent(art);
			return resource(NS.URICISP + art);
		}
		String[] ee = (String[]) ag;
		return makeAnalyst(ee[0], ee[1]);
	}

	private Object getCISEntity(Object a) {
		if (a instanceof String) {
			String art = (String) a;
			createEntity(art);
			return resource(NS.URICISP + art);
		}
		String[] ee = (String[]) a;
		return makeNode(ee[0], ee[1]);
	}

	public void addExtraPropLiteral(String resource, String proptype, String literal) {
		Object node = resource(resource);
		if (!hasProperty(node, proptype)) {
			addProperty(node, call(prov, "createProperty", proptype), call(prov, "createTypedLiteral", literal));
		}
	}

	public void addExtraPropProperty(String resource, String proptype, String property) {
		Object node = resource(resource);
		if (!hasProperty(node, proptype)) {
			addProperty(node, call(prov, "createProperty", proptype), call(prov, "createProperty", property));
		}
	}

	private Object makeNode(String cispid, String text) {
		String nodeid = "Node" + cispid;
		createEntity(nodeid);
		Object node = resource(NS.URICISP + nodeid);
		if (!hasProperty(node, NS.CISP + "infText")) {
			addProperty(node, call(prov, "createProperty", NS.CISP + "infText"), call(prov, "createTypedLiteral", text));
		}
		if (!hasProperty(node, NS.CISP + "nodeID")) {
			addProperty(node, call(prov, "createProperty", NS.CISP + "nodeID"), call(prov, "createTypedLiteral", cispid));
		}
		if (!hasProperty(node, NS.PROV + "type")) {
			addProperty(node, call(prov, "createProperty", NS.PROV + "type"), call(prov, "createProperty", NS.CISP + "Node"));
		}
		return node;
	}

	private Object makePrimarySource(Object ent) {
		if (checkType(ent, "Entity")) {
			if (!hasProperty(ent, NS.PROV + "type")) {
				addProperty(ent, call(prov, "createProperty", NS.PROV + "type"), call(prov, "createProperty", NS.PROV + "PrimarySource"));
			}
		}
		return ent;
	}

	public void makePrimarySource(String string) {
		createEntity(string);
		makePrimarySource(resource(NS.URICISP + string));
	}

	private Object makeAnalyst(String person, String affiliation) {
		createAgent(person);
		createAgent(affiliation);
		addActedOnBehalfOf(person, affiliation);
		Object analyst = resource(NS.URICISP + person);
		if (!hasProperty(analyst, NS.FOAF + "name")) {
			addProperty(analyst, call(prov, "createProperty", NS.FOAF + "name"), call(prov, "createTypedLiteral", person));
		}
		if (!hasProperty(analyst, NS.FOAF + "name")) {
			addProperty(analyst, call(prov, "createProperty", NS.CISP + "role"), call(prov, "createProperty", NS.CISP + "Analyst"));
		}
		return analyst;
	}

	public Object makeGoal(String g1) {
		createEntity(g1);
		Object goal = resource(NS.URICISP + g1);
		if (!hasProperty(goal, NS.PROV + "type")) {
			addProperty(goal, call(prov, "createProperty", NS.PROV + "type"), call(prov, "createProperty", NS.CISP + "Goal"));
		}
		return goal;
	}

	private boolean hasProperty(Object res, String name) {
		return callBool(res, "hasProperty", call(prov, "createProperty", name));
	}

	private Object makeAnalysis(String analyid, String ayid, String location) {
		createEntity(analyid);
		Object node = resource(NS.URICISP + analyid);
		if (!hasProperty(node, NS.CISP + "analysisID")) {
			addProperty(node, call(prov, "createProperty", NS.CISP + "analysisID"), call(prov, "createTypedLiteral", ayid));
		}
		if (!hasProperty(node, NS.PROV + "type")) {
			addProperty(node, call(prov, "createProperty", NS.PROV + "type"), call(prov, "createProperty", NS.CISP + "Analysis"));
		}
		if (location != null) {
			addProperty(node, call(prov, "createProperty", NS.CISP + "location"), call(prov, "createTypedLiteral", location));
		}
		return node;
	}

	private void addWasGeneratedBy(Object a1, Object p1, String time) {
		relate("addWasGeneratedBy", a1, p1);
		if (checkType(a1, "Entity")) {
			if (!hasProperty(a1, NS.PROV + "qualifiedGeneration")) {
				Object gen = call(prov, "createResource");
				addProperty(gen, call(prov, "createProperty", NS.PROV + "atTime"), call(prov, "createTypedLiteral", getCal(time)));
				addProperty(gen, call(prov, "createProperty", NS.PROV + "Activity"), p1);
				addProperty(a1, call(prov, "createProperty", NS.PROV + "qualifiedGeneration"), gen);
			}
		}
	}

	private GregorianCalendar getCal(String date) {
		DateTimeFormatter formatter =
				DateTimeFormat.forPattern("dd-MM-yyyy hh:mm:ss").withOffsetParsed().withZoneUTC();
		DateTime dateTime = formatter.parseDateTime(date);
		return dateTime.toGregorianCalendar();
	}

	public boolean addActedOnBehalfOf(String agent1, String agent2) {
		return callBool(bundle, "addActedOnBehalfOf", NS.URICISP + agent1, NS.URICISP + agent2);
	}

	public boolean addUsed(String activity, String entity) {
		return callBool(bundle, "addUsed", NS.URICISP + activity, NS.URICISP + entity);
	}

	public boolean addWasAssociatedWith(String activity, String agent) {
		return callBool(bundle, "addWasAssociatedWith", NS.URICISP + activity, NS.URICISP + agent);
	}

	public boolean addWasAttributedTo(String entity, String agent) {
		return callBool(bundle, "addWasAttributedTo", NS.URICISP + entity, NS.URICISP + agent);
	}

	public boolean addWasDerivedFrom(String entity1, String entity2) {
		return callBool(bundle, "addWasDerivedFrom", NS.URICISP + entity1, NS.URICISP + entity2);
	}

	public boolean addWasGeneratedBy(String entity, String activity) {
		return callBool(bundle, "addWasGeneratedBy", NS.URICISP + entity, NS.URICISP + activity);
	}

	public boolean addWasInformedBy(String activity1, String activity2) {
		return callBool(bundle, "addWasInformedBy", NS.URICISP + activity1, NS.URICISP + activity2);
	}

	public String createAgent(String agent) {
		return (String) call(bundle, "createAgent", NS.URICISP + agent);
	}

	public String createActivity(String activity) {
		return (String) call(bundle, "createActivity", NS.URICISP + activity);
	}

	public String createEntity(String entity) {
		return (String) call(bundle, "createEntity", NS.URICISP + entity);
	}

	private Object resource(String uri) {
		return call(bundle, "getResource", uri);
	}

	private void relate(String method, Object left, Object right) {
		call(bundle, method, left, right);
	}

	private boolean checkType(Object resource, String type) {
		return callBool(bundle, "checkType", resource, type);
	}

	private void addProperty(Object resource, Object property, Object value) {
		call(resource, "addProperty", property, value);
	}

	private boolean callBool(Object target, String name, Object... args) {
		return (Boolean) call(target, name, args);
	}

	private Object call(Object target, String name, Object... args) {
		try {
			Method method = find(target.getClass(), name, args);
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException) {
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException(target.getClass().getName() + "." + name, cause);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(target.getClass().getName() + "." + name, e);
		}
	}

	private static Method find(Class<?> type, String name, Object[] args) {
		Method best = null;
		int bestScore = Integer.MAX_VALUE;
		for (Method method : type.getMethods()) {
			best = prefer(best, bestScore, method, name, args);
			if (best == method) {
				bestScore = score(method, args);
			}
		}
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			for (Method method : current.getDeclaredMethods()) {
				Method chosen = prefer(best, bestScore, method, name, args);
				if (chosen != best) {
					best = chosen;
					bestScore = score(method, args);
				}
			}
		}
		if (best == null) {
			throw new IllegalStateException("No " + type.getName() + "." + name + " for " + argTypes(args));
		}
		best.setAccessible(true);
		return best;
	}

	private static Method prefer(Method best, int bestScore, Method method, String name, Object[] args) {
		if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) {
			return best;
		}
		int score = score(method, args);
		if (score < bestScore) {
			return method;
		}
		return best;
	}

	private static int score(Method method, Object[] args) {
		Class<?>[] params = method.getParameterTypes();
		int score = 0;
		for (int i = 0; i < params.length; i++) {
			int part = match(params[i], args[i]);
			if (part == Integer.MAX_VALUE) {
				return Integer.MAX_VALUE;
			}
			score += part;
		}
		return score;
	}

	private static int match(Class<?> param, Object arg) {
		if (arg == null) {
			return param.isPrimitive() ? Integer.MAX_VALUE : 5;
		}
		Class<?> argType = arg.getClass();
		Class<?> expected = param.isPrimitive() ? wrap(param) : param;
		if (!expected.isAssignableFrom(argType)) {
			return Integer.MAX_VALUE;
		}
		if (expected.equals(argType)) {
			return 0;
		}
		if (expected.isInterface()) {
			return 1;
		}
		int distance = 0;
		for (Class<?> current = argType; current != null && !current.equals(expected); current = current.getSuperclass()) {
			distance++;
		}
		return distance;
	}

	private static Class<?> wrap(Class<?> primitive) {
		if (primitive == boolean.class) return Boolean.class;
		if (primitive == int.class) return Integer.class;
		if (primitive == long.class) return Long.class;
		if (primitive == double.class) return Double.class;
		if (primitive == float.class) return Float.class;
		if (primitive == short.class) return Short.class;
		if (primitive == byte.class) return Byte.class;
		if (primitive == char.class) return Character.class;
		return primitive;
	}

	private static String argTypes(Object[] args) {
		StringBuilder types = new StringBuilder();
		for (Object arg : args) {
			if (types.length() > 0) {
				types.append(", ");
			}
			types.append(arg == null ? "null" : arg.getClass().getName());
		}
		return types.toString();
	}
}
