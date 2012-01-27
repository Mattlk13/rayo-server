package com.rayo.storage.cassandra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.commons.lang.StringUtils;
import org.scale7.cassandra.pelops.Bytes;
import org.scale7.cassandra.pelops.Cluster;
import org.scale7.cassandra.pelops.Mutator;
import org.scale7.cassandra.pelops.Pelops;
import org.scale7.cassandra.pelops.RowDeletor;
import org.scale7.cassandra.pelops.Selector;
import org.scale7.cassandra.pelops.exceptions.NotFoundException;
import org.scale7.cassandra.pelops.exceptions.PelopsException;

import com.rayo.storage.GatewayDatastore;
import com.rayo.storage.exception.ApplicationAlreadyExistsException;
import com.rayo.storage.exception.ApplicationNotFoundException;
import com.rayo.storage.exception.DatastoreException;
import com.rayo.storage.exception.RayoNodeAlreadyExistsException;
import com.rayo.storage.exception.RayoNodeNotFoundException;
import com.rayo.storage.model.Application;
import com.rayo.storage.model.GatewayCall;
import com.rayo.storage.model.GatewayClient;
import com.rayo.storage.model.RayoNode;
import com.rayo.storage.util.JIDUtils;
import com.voxeo.logging.Loggerf;

/**
 * <p>Cassandra based implementation of the {@link GatewayDatastore} interface.</p> 
 * 
 * <p>You can point this data store to any particular Cassandra installation by 
 * just setting the hostname and port number properties. By default, this store
 * points to localhost/9160.</p> 
 * 
 * @author martin
 *
 */
public class CassandraDatastore implements GatewayDatastore {

	private final static Loggerf log = Loggerf.getLogger(CassandraDatastore.class);
	
	private String hostname = "localhost";
	private String port = "9160";
	private CassandraPrimer primer = null;
	private boolean overrideExistingSchema = true;
	private String schemaName = "rayo";
	private CassandraSchemaHandler schemaHandler = new CassandraSchemaHandler();
	
	public void init() throws Exception {
		
		log.debug("Initializing Cassandra Datastore on [%s:%s]", hostname, port);
		// Pelops throws an exception when no keyspaces are defined and automatic
		// discovery is turned on. So for the first check we disable auto discovery.
		Cluster cluster = new Cluster(hostname, Integer.parseInt(port), false);

		if (overrideExistingSchema || 
			!schemaHandler.schemaExists(cluster, schemaName)) {
			
			// We will create the Cassandra schema if:
			//   1. The property that forces us to create a new schema is set, or
			//   2. The schema has not been created yet
			schemaHandler.buildSchema(cluster, schemaName);
		} else if (!schemaHandler.validSchema(cluster, schemaName)) {
			// if the current schema is somehow screwed, try to fix it
			schemaHandler.buildSchema(cluster, schemaName, false);
		}					
		// try to turn on auto-discovery
		cluster = new Cluster(hostname, Integer.parseInt(port), true);
		Pelops.addPool(schemaName, cluster, schemaName);
		
		if (primer != null) {
			primer.prime(this);
		}
	}

	@Override
	public RayoNode storeNode(RayoNode node) throws DatastoreException {
		
		log.debug("Storing node: [%s]", node);

		RayoNode stored = getNode(node.getHostname());
		if (stored != null) {
			log.error("Node [%s] already exists", node);
			throw new RayoNodeAlreadyExistsException();
		}
		return store(node);
	}
	
	public RayoNode updateNode(RayoNode node) throws DatastoreException {
		
		log.debug("Updating node: [%s]", node);
		
		RayoNode stored = getNode(node.getHostname());
		if (stored == null) {
			log.error("Node [%s] does not exist", node);
			throw new RayoNodeNotFoundException();
		}
			
		return store(node);
	}
	
	private RayoNode store(RayoNode node) throws DatastoreException {
		
		Mutator mutator = Pelops.createMutator(schemaName);
		for (String platform: node.getPlatforms()) {
			mutator.writeSubColumns("nodes", platform, node.getHostname(), 
				mutator.newColumnList(
					mutator.newColumn("priority", String.valueOf(node.getPriority())),
					mutator.newColumn("weight", String.valueOf(node.getWeight())),
					mutator.newColumn("ip", node.getIpAddress()),
					mutator.newColumn("consecutive-errors", String.valueOf(node.getConsecutiveErrors())),
					mutator.newColumn("blacklisted", String.valueOf(node.isBlackListed()))
				)
			);
		}
		
		mutator.writeColumn("ips", Bytes.fromUTF8(node.getIpAddress()), 
				mutator.newColumn(Bytes.fromUTF8("node"), Bytes.fromUTF8(node.getHostname())));		
		
		try {
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Node [%s] stored successfully", node);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException(String.format("Could not create node [%s]", node));
		}
		return node;
	}

	@Override
	public RayoNode removeNode(String rayoNode) throws DatastoreException {
	
		log.debug("Removing node: [%s]", rayoNode);
		RayoNode node = getNode(rayoNode);
		if (node == null) {
			log.error("Node not found: [%s]", rayoNode);
			throw new RayoNodeNotFoundException();
		}
		RowDeletor deletor = Pelops.createRowDeletor(schemaName);
		deletor.deleteRow("ips", node.getIpAddress(), ConsistencyLevel.ONE);

		Mutator mutator = Pelops.createMutator(schemaName);
		for (String platform: node.getPlatforms()) {
			mutator.deleteColumn("nodes", platform, rayoNode);
		}
		
		try {
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Node [%s] deleted successfully", rayoNode);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException("Could not remove node");
		}
		
		return node;
	}
	
	@Override
	public GatewayCall storeCall(GatewayCall call) throws DatastoreException {
		
		log.debug("Storing call: [%s]", call);
		RayoNode node = getNode(call.getNodeJid());
		if (node == null) {
			log.debug("Node [%s] not found for call [%s]", call.getNodeJid(), call);
			throw new RayoNodeNotFoundException();
		}		
		
		Mutator mutator = Pelops.createMutator(schemaName);
		mutator.writeColumns("calls", Bytes.fromUTF8(call.getCallId()), 
			mutator.newColumnList(
					mutator.newColumn(Bytes.fromUTF8("jid"), Bytes.fromUTF8(call.getClientJid())),
					mutator.newColumn(Bytes.fromUTF8("node"), Bytes.fromUTF8(call.getNodeJid()))));
		
		mutator.writeSubColumn("jids", "clients", Bytes.fromUTF8(call.getClientJid()), 
			mutator.newColumn(Bytes.fromUTF8(call.getCallId()), Bytes.fromUTF8(call.getCallId())));
		mutator.writeSubColumn("jids", "nodes", Bytes.fromUTF8(call.getNodeJid()), 
				mutator.newColumn(Bytes.fromUTF8(call.getCallId()), Bytes.fromUTF8(call.getCallId())));
		
		try {
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Call [%s] stored successfully", call);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException("Could not store call");
		}
		
		return call;
	}
	
	@Override
	public GatewayCall getCall(String id) {
		
		log.debug("Getting call with id [%s]", id);
		Selector selector = Pelops.createSelector(schemaName);
		try {
			List<Column> columns = selector.getColumnsFromRow("calls", id, false, ConsistencyLevel.ONE);
			
			return buildCall(columns, id);
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
			return null;
		}
	}
	
	private GatewayCall buildCall(List<Column> columns, String id) {
		
		if (columns != null && columns.size() > 0) {
			GatewayCall call = new GatewayCall();
			call.setCallId(id);
			for(Column column: columns) {
				String name = Bytes.toUTF8(column.getName());
				if (name.equals("node")) {
					call.setNodeJid(Bytes.toUTF8(column.getValue()));
				}
				if (name.equals("jid")) {
					call.setClientJid(Bytes.toUTF8(column.getValue()));
				}
			}
			return call;
		}
		return null;
	}
	
	@Override
	public GatewayCall removeCall(String id) throws DatastoreException {
		
		log.debug("Removing call with id: [%s]", id);
		GatewayCall call = getCall(id);

		Mutator mutator = Pelops.createMutator(schemaName);
		mutator.deleteSubColumns("jids", "clients", call.getClientJid(), id);
		mutator.deleteSubColumns("jids", "nodes", call.getNodeJid(), id);

		try {
			RowDeletor deletor = Pelops.createRowDeletor(schemaName);
			deletor.deleteRow("calls", Bytes.fromUTF8(id), ConsistencyLevel.ONE);
			
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Call [%s] removed successfully", id);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException("Could not remove call");
		}
		
		return call;
	}
	
	@SuppressWarnings("unchecked")
	private Collection<String> getCalls(String jid, String type) {

		try {
			Selector selector = Pelops.createSelector(schemaName);
			List<Column> columns = selector.getSubColumnsFromRow("jids", type, jid, false, ConsistencyLevel.ONE);
			List<String> calls = new ArrayList<String>();
			for(Column column: columns) {
				calls.add(Bytes.toUTF8(column.getValue()));
			}
			return calls;
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
			return Collections.EMPTY_LIST;
		}
	}

	@Override
	public Collection<String> getCalls() {
		
		log.debug("Getting list with all active calls");

		try {
			Selector selector = Pelops.createSelector(schemaName);			
			List<String> calls = new ArrayList<String>();
			List<SuperColumn> cols = selector.getSuperColumnsFromRow("jids", "nodes", false, ConsistencyLevel.ONE);
			for(SuperColumn col: cols) {
				List<Column> columns = col.getColumns();
				for(Column column: columns) {
					calls.add(Bytes.toUTF8(column.getValue()));
				}
			}
			return calls;
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
			return null;
		}
	}
	
	@Override
	public Collection<String> getCallsForNode(String rayoNode) {
		
		log.debug("Finding calls for node: [%s]", rayoNode);
		return getCalls(rayoNode, "nodes");
	}

	@Override
	public Collection<String> getCallsForClient(String jid) {
		
		log.debug("Finding calls for client: [%s]", jid);
		return getCalls(jid, "clients");
	}

	@Override
	public RayoNode getNode(String rayoNode) {
		
		log.debug("Getting node with id: [%s]", rayoNode);
		RayoNode node = null;
		try {
			Selector selector = Pelops.createSelector(schemaName);
			Map<String, List<SuperColumn>> rows = selector.getSuperColumnsFromRowsUtf8Keys(
					"nodes", 
					Selector.newKeyRange("", "", 100), // 100 platforms limit should be enough :)
					Selector.newColumnsPredicate(rayoNode),
					ConsistencyLevel.ONE);
					
			Iterator<Entry<String, List<SuperColumn>>> it = rows.entrySet().iterator();
			while (it.hasNext()) {
				Entry<String, List<SuperColumn>> element = it.next();
				String currPlatform = element.getKey();				
				List<SuperColumn> platformOccurrences= element.getValue();
				for (SuperColumn column: platformOccurrences) {
					if (node == null) {
						node = new RayoNode();
						node = buildNode(column.getColumns());
						node.setHostname(rayoNode);
					}
					node.addPlatform(currPlatform);
				}
			}
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
			return null;
		}
		return node;
	}
	
	@Override
	public String getNodeForCall(String callId) {
		
		log.debug("Finding node for call: [%s]", callId);
		GatewayCall call = getCall(callId);
		if (call != null) {
			return call.getNodeJid();
		}
		return null;
	}

	@Override
	public String getNodeForIpAddress(String ip) {
		
		try {
			log.debug("Finding node for IP address: [%s]", ip);
			Selector selector = Pelops.createSelector(schemaName);
			Column column = selector.getColumnFromRow("ips", ip, "node", ConsistencyLevel.ONE);
			if (column != null) {
				return Bytes.toUTF8(column.getValue());
			}
		} catch (NotFoundException nfe) {
			log.debug("No node found for ip address: [%s]", ip);
			return null;
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
		}	
		return null;
	}

	@Override
	public List<String> getPlatforms() {
		
		log.debug("Returning list with all available platforms");
		return getAllRowNames("nodes",0,false);
	}

	@SuppressWarnings("unchecked")
	public List<RayoNode> getRayoNodesForPlatform(String platformId) {

		try {
			log.debug("Finding rayo nodes for platform: [%s]", platformId);
			Set<String> platforms = new HashSet<String>();
			platforms.add(platformId);
			
			List<RayoNode> nodes = new ArrayList<RayoNode>();
			Selector selector = Pelops.createSelector(schemaName);
			List<SuperColumn> columns = selector.getSuperColumnsFromRow("nodes", platformId, false, ConsistencyLevel.ONE);
			for(SuperColumn column: columns) {
				String id = Bytes.toUTF8(column.getName());
				RayoNode rayoNode = buildNode(column.getColumns());
				rayoNode.setHostname(id);
				rayoNode.setPlatforms(platforms);
				nodes.add(rayoNode);
			}

			return nodes;
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
			return Collections.EMPTY_LIST;
		}
	}
		
	@Override
	public Application storeApplication(Application application) throws DatastoreException {
		
		log.debug("Storing application: [%s]", application);
		if (getApplication(application.getBareJid()) != null) {
			log.error("Application [%s] already exists", application);
			throw new ApplicationAlreadyExistsException();
		}
		
		Mutator mutator = Pelops.createMutator(schemaName);

		mutator.writeColumns("applications", application.getBareJid(), 
			mutator.newColumnList(
					mutator.newColumn(Bytes.fromUTF8("appId"), Bytes.fromUTF8(application.getAppId())),
					mutator.newColumn(Bytes.fromUTF8("platformId"), Bytes.fromUTF8(application.getPlatform())),
					mutator.newColumn(Bytes.fromUTF8("name"), Bytes.fromUTF8(application.getName())),
					mutator.newColumn(Bytes.fromUTF8("accountId"), Bytes.fromUTF8(application.getAccountId())),
					mutator.newColumn(Bytes.fromUTF8("permissions"), Bytes.fromUTF8(application.getPermissions()))));

		try {
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Application [%s] stored successfully", application);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException(String.format("Could not create application [%s]", application));
		}
		
		return application;
	}

	private void populateApplicationData(Application application, List<Column> columns) {
		
		for (Column column: columns) {
			String name = Bytes.toUTF8(column.getName());
			if (name.equals("appId")) {
				application.setAppId(Bytes.toUTF8((column.getValue())));
			}
			if (name.equals("platformId")) {
				application.setPlatform(Bytes.toUTF8((column.getValue())));
			}
			if (name.equals("name")) {
				application.setName(Bytes.toUTF8((column.getValue())));
			}
			if (name.equals("accountId")) {
				application.setAccountId(Bytes.toUTF8((column.getValue())));
			}
			if (name.equals("permissions")) {
				application.setPermissions(Bytes.toUTF8((column.getValue())));
			}
		}
	}
	
	@Override
	public Application getApplication(String jid) {
		
		if (jid == null) return null;
		log.debug("Finding application with jid: [%s]", jid);
		Application application = null;
		
		Selector selector = Pelops.createSelector(schemaName);
		List<Column> columns = selector.getColumnsFromRow("applications", jid, false, ConsistencyLevel.ONE);
		if (columns.size() > 0) {
			application = new Application(jid);
			populateApplicationData(application, columns);
		}
		return application;
	}
	
	@Override
	public Application removeApplication(String id) throws DatastoreException {
		
		log.debug("Removing application with id: [%s]", id);
		Application application = getApplication(id);
		if (application != null) {
			RowDeletor deletor = Pelops.createRowDeletor(schemaName);
			deletor.deleteRow("applications", id, ConsistencyLevel.ONE);
			
			List<String> addresses = getAddressesForApplication(id);
			removeAddresses(addresses);
			
		} else {
			log.debug("No application found with id: [%s]", id);
			throw new ApplicationNotFoundException();
		}
		return application;
	}
	
	@Override
	public List<String> getAddressesForApplication(String appId) {
				
		log.debug("Finding addresses for application id: [%s]", appId);
		return getAllRowNames("addresses", 1, true, appId);
	}

	@Override
	public Application getApplicationForAddress(String address) {

		log.debug("Finding application for address: [%s]", address);
		Selector selector = Pelops.createSelector(schemaName);
		List<Column> columns = selector.getColumnsFromRow("addresses", address, false, ConsistencyLevel.ONE);
		if (columns != null && columns.size() > 0) {
			Column column = columns.get(0);
			return getApplication(Bytes.toUTF8(column.getValue()));
		}
		log.debug("No application found for address: [%s]", address);
		return null;
	}

	@Override
	public void storeAddress(String address, String appId) throws DatastoreException {
	
		ArrayList<String> addresses = new ArrayList<String>();
		addresses.add(address);
		storeAddresses(addresses, appId);
	}
	
	@Override
	public void storeAddresses(Collection<String> addresses, String appId) throws DatastoreException {
		
		log.debug("Storing addresses [%s] on application [%s]", addresses, appId);
		if (getApplication(appId) == null) {
			throw new ApplicationNotFoundException();
		}
		
		Mutator mutator = Pelops.createMutator(schemaName);
		for (String address: addresses) {
			mutator.writeColumn("addresses", Bytes.fromUTF8(address), 
					mutator.newColumn(appId, appId));
		}
		
		try {
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Addresses [%s] stored successfully on application [%s]", addresses, appId);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException(String.format("Could not add addresses [%s] to application [%s]", addresses, appId));
		}
	}
	
	@Override
	public void removeAddress(String address) throws DatastoreException {
		
		Application application = getApplicationForAddress(address);
		if (application != null) {
			List<String> addresses = new ArrayList<String>();
			addresses.add(address);
			removeAddresses(addresses);
		}
	}

	private void removeAddresses(List<String> addresses) throws DatastoreException {
		
		log.debug("Removing addresses [%s]", addresses);
		RowDeletor deletor = Pelops.createRowDeletor(schemaName);
		for (String address: addresses) {
			deletor.deleteRow("addresses", address, ConsistencyLevel.ONE);
		}
	}

	@Override
	public GatewayClient storeClient(GatewayClient client) throws DatastoreException {
		
		log.debug("Storing client: [%s]", client);
		Application application = getApplication(client.getBareJid());
		if (application == null) {
			log.debug("Client [%s] already exists", client);
			throw new ApplicationNotFoundException();
		}
		
		Mutator mutator = Pelops.createMutator(schemaName);
		mutator.writeColumns("clients", client.getBareJid(),
			mutator.newColumnList(
				mutator.newColumn(client.getResource(), client.getResource())));
		try {
			mutator.execute(ConsistencyLevel.ONE);
			log.debug("Client [%s] stored successfully", client);
		} catch (Exception e) {
			log.error(e.getMessage(),e);
			throw new DatastoreException(String.format("Could not create client application [%s]", client));
		}
		
		return client;
	}
	
	@Override
	public GatewayClient removeClient(String jid) throws DatastoreException {
		
		log.debug("Removing client with jid: [%s]", jid);
		GatewayClient client = getClient(jid);
		if (client != null) {
			Mutator mutator = Pelops.createMutator(schemaName);
			String bareJid = JIDUtils.getBareJid(jid);
			String resource = JIDUtils.getResource(jid);
			mutator.deleteColumn("clients", bareJid, resource);
			mutator.execute(ConsistencyLevel.ONE);
			
			List<String> resources = getClientResources(bareJid);
			if (resources.size() == 0) {
				RowDeletor deletor = Pelops.createRowDeletor(schemaName);
				deletor.deleteRow("clients", bareJid, ConsistencyLevel.ONE);				
			}			
			log.debug("Client with jid: [%s] removed successfully", jid);
		}
		
		return client;
	}
	
	@Override
	public GatewayClient getClient(String jid) {
				
		log.debug("Finding client with jid: [%s]", jid);
		GatewayClient client = null;
		try {
			String bareJid = JIDUtils.getBareJid(jid);
			String resource = JIDUtils.getResource(jid);
			boolean resourceFound = false;
			
			Selector selector = Pelops.createSelector(schemaName);
			List<Column> columns = selector.getColumnsFromRow("clients", bareJid, false, ConsistencyLevel.ONE);
			if (columns != null && columns.size() > 0) {
				for(Column column: columns) {
					String name = Bytes.toUTF8(column.getName());
					if (name.equals(resource)) {
						resourceFound = true;
					}
				}
			}
			
			if (resourceFound) {
				Application application = getApplication(JIDUtils.getBareJid(jid));
				if (application != null) {
					client =  new GatewayClient();
					client.setJid(jid);
					client.setPlatform(application.getPlatform());
				}
			}
			
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
		}	
		return client;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<String> getClientResources(String bareJid) {
						
		try {
			log.debug("Finding resources for clients with jid: [%s]", bareJid);
			Selector selector = Pelops.createSelector(schemaName);
			List<Column> resourceColumn = selector.getColumnsFromRow("clients", bareJid, false, ConsistencyLevel.ONE);			
			List<String> resources = new ArrayList<String>();
			for(Column column: resourceColumn) {
				String name = Bytes.toUTF8(column.getName());
				if (!name.equals("appId")) {
					resources.add(Bytes.toUTF8(column.getName()));
				}
			}
			return resources;
		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
			return Collections.EMPTY_LIST;
		}
	}
	
	private RayoNode buildNode(List<Column> columns) {
		
		if (columns != null && columns.size() > 0) {
			RayoNode node = new RayoNode();
			for(Column column: columns) {
				String name = Bytes.toUTF8(column.getName());
				if (name.equals("ip")) {
					node.setIpAddress(Bytes.toUTF8(column.getValue()));
				}
				if (name.equals("weight")) {
					node.setWeight(Integer.parseInt(Bytes.toUTF8(column.getValue())));
				}
				if (name.equals("priority")) {
					node.setPriority(Integer.parseInt(Bytes.toUTF8(column.getValue())));
				}
				if (name.equals("consecutive-errors")) {
					node.setConsecutiveErrors(Integer.parseInt(Bytes.toUTF8(column.getValue())));
				}
				if (name.equals("blacklisted")) {
					node.setBlackListed(Boolean.valueOf(Bytes.toUTF8(column.getValue())));
				}
				if (name.equals("platforms")) {
					node.setPlatforms(new HashSet<String>(Arrays.asList(StringUtils.split(Bytes.toUTF8(column.getValue()),","))));
				}
			}
			return node;
		}
		return null;
	}
		
	@Override
	public List<String> getClients() {
	
		log.debug("Returning all clients");
		return getAllRowNames("clients", 1, true);
	}	

	private List<String> getAllRowNames(String columnFamily, int numColumns, boolean excludeIfLessColumns) {

		return getAllRowNames(columnFamily, numColumns, excludeIfLessColumns, null);
	}
	
	private List<String> getAllRowNames(String columnFamily, int numColumns, boolean excludeIfLessColumns, String colName) {

		List<String> result = new ArrayList<String>();
		try {
			Selector selector = Pelops.createSelector(schemaName);
			final int PAGE_SIZE = 100;
			String currRow = "";
			while (true) {
				SlicePredicate predicate = null;
				if (colName == null) {
					predicate = Selector.newColumnsPredicateAll(false, numColumns);
				} else {
					predicate = Selector.newColumnsPredicate(colName,colName,false,numColumns);
				}
				
				Map<String, List<Column>> rows =
					selector.getColumnsFromRowsUtf8Keys(
						columnFamily,
						Selector.newKeyRange(currRow, "", PAGE_SIZE),
						predicate,
						ConsistencyLevel.ONE);

				Iterator<Entry<String, List<Column>>> it = rows.entrySet().iterator();
				while (it.hasNext()) {
					Entry<String, List<Column>> element = it.next();
					if (excludeIfLessColumns && element.getValue().size() < numColumns) {
						continue;
					}
					currRow = element.getKey();
					if (!result.contains(currRow)) {
						result.add(currRow);
					}
				}

				if (rows.keySet().size() < PAGE_SIZE)
					break;
			}			

		} catch (PelopsException pe) {
			log.error(pe.getMessage(),pe);
		}		
		
		return result;
	}
	
	/**
	 * Gets the domain that Cassandra is running on
	 * 
	 * @return String Cassandra domain
	 */
	public String getHostname() {
		return hostname;
	}

	/**
	 * Sets the domain that Cassandra is running on
	 * 
	 * @param hostname Cassandra domain
	 */
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	/**
	 * Gets the port that Cassandra is running on
	 * 
	 * @return int Cassandra port
	 */
	public String getPort() {
		return port;
	}

	/**
	 * Sets the port Cassandra is running on
	 * 
	 * @param port Cassandra port
	 */
	public void setPort(String port) {
		this.port = port;
	}

	/**
	 * Tells is schema is going to be overriding on startup or not
	 * 
	 * @return boolean Override schema
	 */
	public boolean isOverrideExistingSchema() {
		return overrideExistingSchema;
	}

	/**
	 * <p>Sets whether the current Cassandra schema should be overriden after startup 
	 * or not. If <code>true</code> this Cassandra Datastore will drop the existing 
	 * schema and will create a new one on initialization. If <code>false</code> then 
	 * the Datastore will try to use the existing schema.</p> 
	 * 
	 * @param overrideExistingSchema
	 */
	public void setOverrideExistingSchema(boolean overrideExistingSchema) {
		this.overrideExistingSchema = overrideExistingSchema;
	}

	public void setSchemaName(String schemaName) {
		this.schemaName = schemaName;
	}

	public CassandraSchemaHandler getSchemaHandler() {
		return schemaHandler;
	}

	public void setSchemaHandler(CassandraSchemaHandler schemaHandler) {
		this.schemaHandler = schemaHandler;
	}

	public void setPrimer(CassandraPrimer primer) {
		this.primer = primer;
	}
}
