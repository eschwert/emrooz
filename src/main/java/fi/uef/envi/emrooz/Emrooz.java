/*
 * Copyright (C) 2015 see CREDITS.txt
 * All rights reserved.
 */

package fi.uef.envi.emrooz;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;

import com.carmatech.cassandra.TimeUUID;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.TableMetadata;

import fi.uef.envi.emrooz.entity.TemporalEntityVisitor;
import fi.uef.envi.emrooz.entity.ssn.FeatureOfInterest;
import fi.uef.envi.emrooz.entity.ssn.Property;
import fi.uef.envi.emrooz.entity.ssn.Sensor;
import fi.uef.envi.emrooz.entity.ssn.SensorObservation;
import fi.uef.envi.emrooz.entity.time.Instant;
import fi.uef.envi.emrooz.query.SensorObservationQuery;
import fi.uef.envi.emrooz.rdf.RDFEntityRepresenter;
import fi.uef.envi.emrooz.utils.ConverterUtil;
import static fi.uef.envi.emrooz.EmroozOptions.HOST;
import static fi.uef.envi.emrooz.EmroozOptions.KEYSPACE;
import static fi.uef.envi.emrooz.EmroozOptions.DATA_TABLE;
import static fi.uef.envi.emrooz.EmroozOptions.REGISTRATIONS_TABLE;
import static fi.uef.envi.emrooz.EmroozOptions.ROWKEY_DATETIME_PATTERN;
import static fi.uef.envi.emrooz.EmroozOptions.DATA_TABLE_ATTRIBUTE_1;
import static fi.uef.envi.emrooz.EmroozOptions.DATA_TABLE_ATTRIBUTE_2;
import static fi.uef.envi.emrooz.EmroozOptions.DATA_TABLE_ATTRIBUTE_3;
import static fi.uef.envi.emrooz.EmroozOptions.REGISTRATIONS_TABLE_ATTRIBUTE_1;
import static fi.uef.envi.emrooz.EmroozOptions.REGISTRATIONS_TABLE_ATTRIBUTE_2;
import static fi.uef.envi.emrooz.EmroozOptions.REGISTRATIONS_TABLE_ATTRIBUTE_3;
import static fi.uef.envi.emrooz.EmroozOptions.REGISTRATIONS_TABLE_ATTRIBUTE_4;
import static fi.uef.envi.emrooz.EmroozOptions.REGISTRATIONS_TABLE_ATTRIBUTE_5;

/**
 * <p>
 * Title: Emrooz
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Project: Emrooz
 * </p>
 * <p>
 * Copyright: Copyright (C) 2015
 * </p>
 * 
 * @author Markus Stocker
 */

public class Emrooz {
	private String host = HOST;

	private Cluster cluster;
	private Session session;
	private Map<String, Registration> registrations;
	private Map<URI, Map<URI, Map<URI, String>>> registrationIdsMap;

	private PreparedStatement sensorObservationInsertStatement;
	private PreparedStatement sensorObservationSelectStatement;
	private PreparedStatement registrationInsertStatement;
	private String registrationSelectStatement;

	private DateTimeFormatter dtfRowKey = DateTimeFormat
			.forPattern(ROWKEY_DATETIME_PATTERN);

	private DateTime instant = null;
	private final TemporalEntityVisitor temporalEntityVisitor;
	private final RDFEntityRepresenter representer;

	private static final Logger log = Logger.getLogger(Emrooz.class.getName());

	public Emrooz() {
		this(null);
	}

	public Emrooz(String host) {
		if (host != null)
			this.host = host;

		this.cluster = Cluster.builder().addContactPoint(this.host).build();
		this.registrations = new HashMap<String, Registration>();
		this.registrationIdsMap = new HashMap<URI, Map<URI, Map<URI, String>>>();
		this.temporalEntityVisitor = new EmroozTemporalEntityVisitor();
		this.representer = new RDFEntityRepresenter();

		initialize();
		connect();

		this.registrationInsertStatement = session.prepare("INSERT INTO "
				+ KEYSPACE + "." + REGISTRATIONS_TABLE + " ("
				+ REGISTRATIONS_TABLE_ATTRIBUTE_1 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_2 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_3 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_4 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_5 + ") VALUES (?,?,?,?,?)");
		this.registrationSelectStatement = "SELECT "
				+ REGISTRATIONS_TABLE_ATTRIBUTE_1 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_2 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_3 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_4 + ","
				+ REGISTRATIONS_TABLE_ATTRIBUTE_5 + " FROM " + KEYSPACE + "."
				+ REGISTRATIONS_TABLE;

		registrations();

		this.sensorObservationInsertStatement = session.prepare("INSERT INTO "
				+ KEYSPACE + "." + DATA_TABLE + " (" + DATA_TABLE_ATTRIBUTE_1
				+ "," + DATA_TABLE_ATTRIBUTE_2 + "," + DATA_TABLE_ATTRIBUTE_3
				+ ") VALUES (?, ?, ?)");
		this.sensorObservationSelectStatement = session.prepare("SELECT "
				+ DATA_TABLE_ATTRIBUTE_3 + " FROM " + KEYSPACE + "."
				+ DATA_TABLE + " WHERE " + DATA_TABLE_ATTRIBUTE_1 + "=? AND "
				+ DATA_TABLE_ATTRIBUTE_2 + ">=minTimeuuid(?) AND "
				+ DATA_TABLE_ATTRIBUTE_2 + "<minTimeuuid(?)");

	}

	public String getHost() {
		return host;
	}

	public void register(Sensor sensor, Property property,
			FeatureOfInterest feature, Rollover rollover) {
		if (sensor == null || property == null || feature == null
				|| rollover == null) {
			if (log.isLoggable(Level.WARNING))
				log.warning("At least one parameter is null [sensor = "
						+ sensor + "; property = " + property + "; feature = "
						+ feature + "; rollover = " + rollover + "]");
			return;
		}

		register(sensor.getId(), property.getId(), feature.getId(), rollover);
	}

	public void register(URI sensor, URI property, URI feature,
			Rollover rollover) {
		Registration registration = new Registration(sensor, property, feature,
				rollover);

		String registrationId = registration.getId();

		if (registrations.containsKey(registrationId)) {
			if (log.isLoggable(Level.WARNING))
				log.warning("Registrations exists [registration = "
						+ registration + "]");

			return;
		}

		session.execute(new BoundStatement(registrationInsertStatement).bind(
				registrationId, registration.getSensor().stringValue(),
				registration.getProperty().stringValue(), registration
						.getFeature().stringValue(), registration.getRollover()
						.toString()));

		registrations();
	}

	public void add(SensorObservation observation) {
		if (observation == null) {
			if (log.isLoggable(Level.WARNING))
				log.warning("Null parameter not allowed [observation = null]");
			return;
		}

		instant = null;

		observation.getObservationResultTime().accept(temporalEntityVisitor);

		addSensorObservation(observation.getSensor(),
				observation.getProperty(), observation.getFeatureOfInterest(),
				instant, representer.createRepresentation(observation));
	}

	public void addSensorObservation(Sensor sensor, Property property,
			FeatureOfInterest feature, DateTime resultTime,
			Set<Statement> statements) {
		if (sensor == null || property == null || feature == null
				|| resultTime == null) {
			if (log.isLoggable(Level.WARNING))
				log.warning("At least one parameter is null [sensor = "
						+ sensor + "; property = " + property + "; feature = "
						+ feature + "; resultTime = " + resultTime + "]");
			return;
		}

		if (statements.isEmpty()) {
			if (log.isLoggable(Level.WARNING))
				log.warning("Empty collection of statements [sensor = "
						+ sensor + "; property = " + property + "; feature = "
						+ feature + "; resultTime = " + resultTime
						+ "; statements = " + statements + "]");
			return;
		}

		addSensorObservation(sensor.getId(), property.getId(), feature.getId(),
				resultTime, statements);
	}

	public void addSensorObservation(URI sensor, URI property, URI feature,
			DateTime resultTime, Set<Statement> statements) {
		addSensorObservation(getRowKey(sensor, property, feature, resultTime),
				resultTime, statements);
	}

	public void addSensorObservation(Set<Statement> statements) {
		SensorObservation observation = representer
				.createSensorObservation(statements);

		URI sensor = observation.getSensor().getId();
		URI property = observation.getProperty().getId();
		URI feature = observation.getFeatureOfInterest().getId();

		observation.getObservationResultTime().accept(temporalEntityVisitor);

		DateTime resultTime = instant;

		if (sensor == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Failed to extract sensor from observation [sensor = "
						+ sensor + "; statements = " + statements + "]");

			return;
		}

		if (property == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Failed to extract property from observation [property = "
						+ property + "; statements = " + statements + "]");

			return;
		}

		if (feature == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Failed to extract feature from observation [feature = "
						+ feature + "; statements = " + statements + "]");

			return;
		}

		if (resultTime == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Failed to extract time from observation [resultTime = "
						+ resultTime + "; statements = " + statements + "]");

			return;
		}

		addSensorObservation(sensor, property, feature, resultTime, statements);
	}

	public fi.uef.envi.emrooz.query.ResultSet evaluate(
			SensorObservationQuery query) {
		return new fi.uef.envi.emrooz.query.ResultSet(query,
				getSensorObservations(query.getSensorId(),
						query.getPropertyId(), query.getFeatureOfInterestId(),
						query.getTimeFrom(), query.getTimeTo()));
	}

	public void close() {
		cluster.close();
	}

	private void initialize() {
		Session session = cluster.connect();
		Metadata metadata = cluster.getMetadata();
		KeyspaceMetadata keyspaceMetadata = metadata.getKeyspace(KEYSPACE);

		if (keyspaceMetadata == null) {
			session.execute("CREATE KEYSPACE "
					+ KEYSPACE
					+ " WITH REPLICATION = { 'class' : 'org.apache.cassandra.locator.SimpleStrategy', 'replication_factor': '1' } AND DURABLE_WRITES = true;");
		}

		session = cluster.connect(KEYSPACE);
		metadata = cluster.getMetadata();
		keyspaceMetadata = metadata.getKeyspace(KEYSPACE);
		TableMetadata dataTableMetadata = keyspaceMetadata.getTable(DATA_TABLE);

		if (dataTableMetadata == null) {
			session.execute("CREATE TABLE "
					+ KEYSPACE
					+ "."
					+ DATA_TABLE
					+ " ("
					+ DATA_TABLE_ATTRIBUTE_1
					+ " ascii,"
					+ DATA_TABLE_ATTRIBUTE_2
					+ " timeuuid,"
					+ DATA_TABLE_ATTRIBUTE_3
					+ " blob,PRIMARY KEY ("
					+ DATA_TABLE_ATTRIBUTE_1
					+ ", "
					+ DATA_TABLE_ATTRIBUTE_2
					+ ")) WITH COMPACT STORAGE AND read_repair_chance = 0.0 AND dclocal_read_repair_chance = 0.1 AND gc_grace_seconds = 864000 AND bloom_filter_fp_chance = 0.01 AND caching = { 'keys' : 'ALL', 'rows_per_partition' : 'NONE' } AND comment = '' AND compaction = { 'class' : 'org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy' } AND compression = { 'sstable_compression' : 'org.apache.cassandra.io.compress.LZ4Compressor' } AND default_time_to_live = 0 AND speculative_retry = 'NONE' AND min_index_interval = 128 AND max_index_interval = 2048;");
		}

		TableMetadata registrationsTableMetadata = keyspaceMetadata
				.getTable(REGISTRATIONS_TABLE);

		if (registrationsTableMetadata == null) {
			session.execute("CREATE TABLE " + KEYSPACE + "."
					+ REGISTRATIONS_TABLE + " ("
					+ REGISTRATIONS_TABLE_ATTRIBUTE_1 + " ascii PRIMARY KEY, "
					+ REGISTRATIONS_TABLE_ATTRIBUTE_2 + " ascii, "
					+ REGISTRATIONS_TABLE_ATTRIBUTE_3 + " ascii, "
					+ REGISTRATIONS_TABLE_ATTRIBUTE_4 + " ascii, "
					+ REGISTRATIONS_TABLE_ATTRIBUTE_5 + " ascii);");
		}
	}

	private void registrations() {
		registrations.clear();
		registrationIdsMap.clear();

		com.datastax.driver.core.ResultSet rows = session
				.execute(registrationSelectStatement);

		for (Row row : rows) {
			String id = row.getString(REGISTRATIONS_TABLE_ATTRIBUTE_1);
			String s = row.getString(REGISTRATIONS_TABLE_ATTRIBUTE_2);
			String p = row.getString(REGISTRATIONS_TABLE_ATTRIBUTE_3);
			String f = row.getString(REGISTRATIONS_TABLE_ATTRIBUTE_4);
			String r = row.getString(REGISTRATIONS_TABLE_ATTRIBUTE_5);

			Registration registration = new Registration(s, p, f, r);

			String registrationId = registration.getId();
			URI sensor = registration.getSensor();
			URI property = registration.getProperty();
			URI feature = registration.getFeature();

			if (!id.equals(registrationId)) {
				if (log.isLoggable(Level.SEVERE))
					log.severe("Unexpected difference in registration ids; persisted registration is not cached [id = "
							+ id
							+ "; registrationId = "
							+ registrationId
							+ "; sensor = "
							+ sensor
							+ "; property = "
							+ property + "; feature = " + feature + "]");

				continue;
			}

			registrations.put(id, registration);

			Map<URI, Map<URI, String>> m1 = registrationIdsMap.get(sensor);

			if (m1 == null) {
				m1 = new HashMap<URI, Map<URI, String>>();
				registrationIdsMap.put(sensor, m1);
			}

			Map<URI, String> m2 = m1.get(property);

			if (m2 == null) {
				m2 = new HashMap<URI, String>();
				m1.put(property, m2);
			}

			m2.put(feature, id);
		}
	}

	private void addSensorObservation(String rowKey, DateTime resultTime,
			Set<Statement> columnValue) {
		addSensorObservation(rowKey, TimeUUID.toUUID(resultTime), columnValue);
	}

	private void addSensorObservation(String rowKey, UUID resultTime,
			Set<Statement> columnValue) {
		addSensorObservation(rowKey, resultTime,
				ConverterUtil.toByteArray(columnValue));
	}

	private void addSensorObservation(String rowKey, UUID columnName,
			byte[] columnValue) {
		if (rowKey == null || columnName == null || columnValue == null) {
			if (log.isLoggable(Level.WARNING))
				log.warning("At least one parameter is null (possibly the byte[] columnValue [rowKey = "
						+ rowKey + "; columnName = " + columnName + "]");
			return;
		}

		session.execute(new BoundStatement(sensorObservationInsertStatement)
				.bind(rowKey, columnName, ByteBuffer.wrap(columnValue)));
	}

	private Set<Statement> getSensorObservations(URI sensor, URI property,
			URI feature, DateTime timeFrom, DateTime timeTo) {
		if (sensor == null || property == null || feature == null
				|| timeFrom == null || timeTo == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("At least one parameter is null; returned empty set [sensor = "
						+ sensor
						+ "; property = "
						+ property
						+ "; feature = "
						+ feature
						+ "; timeFrom = "
						+ timeFrom
						+ "; timeTo = "
						+ timeTo + "]");

			return Collections.emptySet();
		}

		String registrationId = getCachedRegistrationId(sensor, property,
				feature);

		if (registrationId == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Registration id not found in cache [sensor = "
						+ sensor + "; propery = " + property + "; feature = "
						+ feature + "]");

			return null;
		}

		Registration registration = registrations.get(registrationId);

		if (registration == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Registration not found [sensor = " + sensor
						+ "; propery = " + property + "; feature = " + feature
						+ "]");

			return null;
		}

		Rollover rollover = registration.getRollover();

		if (rollover == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Registration rollover is null [registration = "
						+ registration + "]");

			return null;
		}

		DateTime time = timeFrom;
		Set<Statement> ret = new HashSet<Statement>();

		while (time.isBefore(timeTo)) {
			ret.addAll(getSensorObservations(
					getRowKey(sensor, property, feature, time), time, timeTo));

			if (rollover.equals(Rollover.YEAR))
				time = time.year().roundFloorCopy().plusYears(1);
			else if (rollover.equals(Rollover.MONTH))
				time = time.monthOfYear().roundFloorCopy().plusMonths(1);
			else if (rollover.equals(Rollover.DAY))
				time = time.dayOfMonth().roundFloorCopy().plusDays(1);
			else if (rollover.equals(Rollover.HOUR))
				time = time.hourOfDay().roundFloorCopy().plusHours(1);
			else if (rollover.equals(Rollover.MINUTE))
				time = time.minuteOfHour().roundFloorCopy().plusMinutes(1);
			else
				throw new RuntimeException("Unsupported rollover [rollover = "
						+ rollover + "]");
		}

		return Collections.unmodifiableSet(ret);
	}

	private Set<Statement> getSensorObservations(String rowKey,
			DateTime timeFrom, DateTime timeTo) {
		if (timeFrom == null || timeTo == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Parameters cannot be null; returned empty result set [timeFrom = "
						+ timeFrom + "; timeTo = " + timeTo + "]");

			return Collections.emptySet();
		}

		return getSensorObservations(rowKey, timeFrom.toDate(), timeTo.toDate());
	}

	private Set<Statement> getSensorObservations(String rowKey,
			Date columnNameFrom, Date columnNameTo) {
		if (rowKey == null || columnNameFrom == null || columnNameTo == null) {
			if (log.isLoggable(Level.WARNING))
				log.warning("Returned empty result set [rowKey = " + rowKey
						+ "; columnNameFrom = " + columnNameFrom
						+ "; columnNameTo = " + columnNameTo + "]");

			return Collections.emptySet();
		}

		return ConverterUtil.toStatements(session.execute(new BoundStatement(
				sensorObservationSelectStatement).bind(rowKey, columnNameFrom,
				columnNameTo)));
	}

	private String getRowKey(URI sensor, URI property, URI feature,
			DateTime time) {
		if (sensor == null || property == null || feature == null
				|| time == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Parameters cannot be null [sensor = " + sensor
						+ "; propery = " + property + "; feature = " + feature
						+ "; time = " + time + "]");

			return null;
		}

		String registrationId = getCachedRegistrationId(sensor, property,
				feature);

		if (registrationId == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Registration id not found in cache [sensor = "
						+ sensor + "; propery = " + property + "; feature = "
						+ feature + "]");

			return null;
		}

		Registration registration = registrations.get(registrationId);

		if (registration == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Registration not found [sensor = " + sensor
						+ "; propery = " + property + "; feature = " + feature
						+ "]");

			return null;
		}

		Rollover rollover = registration.getRollover();

		if (rollover == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Registration rollover is null [registration = "
						+ registration + "]");

			return null;
		}

		if (rollover.equals(Rollover.YEAR))
			time = time.year().roundFloorCopy();
		else if (rollover.equals(Rollover.MONTH))
			time = time.monthOfYear().roundFloorCopy();
		else if (rollover.equals(Rollover.DAY))
			time = time.dayOfMonth().roundFloorCopy();
		else if (rollover.equals(Rollover.HOUR))
			time = time.hourOfDay().roundFloorCopy();
		else if (rollover.equals(Rollover.MINUTE))
			time = time.minuteOfHour().roundFloorCopy();
		else
			throw new RuntimeException("Unsupported rollover [rollover = "
					+ rollover + "]");

		return registrationId + "-" + dtfRowKey.print(time);
	}

	private String getCachedRegistrationId(URI sensor, URI property, URI feature) {
		Map<URI, Map<URI, String>> m1 = registrationIdsMap.get(sensor);

		if (m1 == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Sensor not registered [sensor = " + sensor
						+ "; registrationIdsMap = " + registrationIdsMap + "]");

			return null;
		}

		Map<URI, String> m2 = m1.get(property);

		if (m2 == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Property not registered [sensor = " + sensor
						+ "; property = " + property
						+ "; registrationIdsMap = " + registrationIdsMap + "]");

			return null;
		}

		String ret = m2.get(feature);

		if (ret == null) {
			if (log.isLoggable(Level.SEVERE))
				log.severe("Feature not registered [sensor = " + sensor
						+ "; property = " + property + "; feature = " + feature
						+ "; registrationIdsMap = " + registrationIdsMap + "]");

			return null;
		}

		return ret;
	}

	private void connect() {
		session = cluster.connect(KEYSPACE);
	}

	private class EmroozTemporalEntityVisitor implements TemporalEntityVisitor {

		@Override
		public void visit(Instant entity) {
			instant = entity.getValue();
		}

	}

	// private URI resolve(StatementPattern pattern,
	// List<StatementPattern> patterns, Set<Statement> graph) {
	// // Try to resolve resource subject by first getting the joined triple
	// // patterns matching resource subject and then execute a SPARQL query
	// // with the triple patterns over graph to resolve resource
	//
	// Set<StatementPattern> basicGraphPattern = new
	// HashSet<StatementPattern>();
	//
	// Var findVar = pattern.getObjectVar();
	//
	// find(findVar, patterns, basicGraphPattern);
	//
	// GraphPattern gp = new GraphPattern();
	//
	// for (StatementPattern bgp : basicGraphPattern) {
	// gp.addRequiredSP(bgp.getSubjectVar(), bgp.getPredicateVar(),
	// bgp.getObjectVar());
	// }
	//
	// TupleExpr query = new Projection(gp.buildTupleExpr(),
	// new ProjectionElemList(new ProjectionElem(findVar.getName())));
	//
	// try {
	// Repository repo = new SailRepository(new MemoryStore());
	// repo.initialize();
	//
	// SailRepositoryConnection conn = (SailRepositoryConnection) repo
	// .getConnection();
	//
	// for (Statement statement : graph) {
	// conn.add(statement);
	// }
	//
	// ParsedTupleQuery tp = new ParsedTupleQuery(query);
	// SailTupleQuery q = new SailTupleQuery(tp, conn);
	//
	// TupleQueryResult r = q.evaluate();
	//
	// while (r.hasNext()) {
	//
	// }
	//
	// conn.close();
	// } catch (RepositoryException | MalformedQueryException
	// | QueryEvaluationException e) {
	// e.printStackTrace();
	// }
	//
	// return null;
	// }

	// private void find(Var s, List<StatementPattern> patterns,
	// Set<StatementPattern> ret) {
	// for (StatementPattern pattern : patterns) {
	// if (!s.equals(pattern.getSubjectVar()))
	// continue;
	//
	// ret.add(pattern);
	//
	// find(pattern.getObjectVar(), patterns, ret);
	// }
	// }

}
