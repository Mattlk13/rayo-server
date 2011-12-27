package com.rayo.gateway.memory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.rayo.gateway.GatewayDatastore;
import com.rayo.gateway.exception.ApplicationAlreadyExistsException;
import com.rayo.gateway.exception.ApplicationNotFoundException;
import com.rayo.gateway.exception.DatastoreException;
import com.rayo.gateway.exception.RayoNodeAlreadyExistsException;
import com.rayo.gateway.exception.RayoNodeNotFoundException;
import com.rayo.gateway.model.Application;
import com.rayo.gateway.model.GatewayCall;
import com.rayo.gateway.model.GatewayClient;
import com.rayo.gateway.model.RayoNode;
import com.rayo.gateway.util.JIDUtils;

/**
 * <p>Fully in-memory Map based implementation of the {@link GatewayDatastore} interface.</p> 
 * 
 * <p>This datastore is not intended to be usable in clustered Gateways as it will only 
 * work on a single-box scenario. It is provided as a reference implementation and can be 
 * useful if you plan to use only a single Gateway.</p> 
 * 
 * @author martin
 *
 */
public class InMemoryDatastore implements GatewayDatastore {

	private ReadWriteLock applicationsLock = new ReentrantReadWriteLock();
	private ReadWriteLock nodesLock = new ReentrantReadWriteLock();
	private ReadWriteLock callsLock = new ReentrantReadWriteLock();
	
	private Map<String, RayoNode> nodesMap = new ConcurrentHashMap<String, RayoNode>();
	private Map<String, RayoNode> ipsMap = new ConcurrentHashMap<String, RayoNode>();
	private Map<String, List<RayoNode>> platformsMap = new ConcurrentHashMap<String, List<RayoNode>>();

	private Map<String, GatewayCall> callsMap = new ConcurrentHashMap<String, GatewayCall>();
	private Map<String, List<GatewayCall>> jidsMap = new ConcurrentHashMap<String, List<GatewayCall>>();

	private Map<String, GatewayClient> clientsMap = new ConcurrentHashMap<String, GatewayClient>();
	private Map<String, List<String>> resourcesMap = new ConcurrentHashMap<String, List<String>>();
	private Map<String, Application> applicationsMap = new ConcurrentHashMap<String, Application>();
	private Map<String, Application> addressesMap = new ConcurrentHashMap<String, Application>();
	private Map<String, List<String>> appToAddressesMap = new ConcurrentHashMap<String, List<String>>();
	
	@Override
	public RayoNode storeNode(RayoNode node) throws DatastoreException {

		if (getNode(node.getHostname()) != null) {
			throw new RayoNodeAlreadyExistsException();
		}
		Lock nodeLock = nodesLock.writeLock();
		nodeLock.lock();
		try {
			nodesMap.put(node.getHostname(), node);
			ipsMap.put(node.getIpAddress(), node);
			for(String platform: node.getPlatforms()) {
				List<RayoNode> nodes = platformsMap.get(platform);
				if (nodes == null) {
					nodes = new ArrayList<RayoNode>();
					platformsMap.put(platform, nodes);
				}
				if(!nodes.contains(node)) {
					nodes.add(node);
				}
			}
		} finally {
			nodeLock.unlock();
		}				
		return node;
	}

	@Override
	public RayoNode removeNode(String id) throws DatastoreException {

		Lock nodeLock = nodesLock.writeLock();
		nodeLock.lock();
		try {
			RayoNode node = getNode(id);
			if (node ==  null) {
				throw new RayoNodeNotFoundException();
			}
			nodesMap.remove(node.getHostname());
			ipsMap.remove(node.getIpAddress());
			
			for(String platform: node.getPlatforms()) {
				List<RayoNode> nodes = platformsMap.get(platform);
				if (nodes != null) {
					nodes.remove(node);
				}
			}
			
			return node;
		} finally {
			nodeLock.unlock();
		}
	}
	
	@Override
	public String getNodeForCall(String callId) {
		
		GatewayCall call = getCall(callId);
		if (call != null) {
			return call.getNodeJid();
		}
		return null;
	}
	
	@Override
	public RayoNode getNode(String id) {
		
		Lock nodeLock = nodesLock.readLock();
		nodeLock.lock();
		try {
			return nodesMap.get(id);
		} finally {
			nodeLock.unlock();
		}
	}
	
	public List<RayoNode> getRayoNodesForPlatform(String platformId) {
		
		Lock nodeLock = nodesLock.readLock();
		nodeLock.lock();
		try {
			List<RayoNode> nodes = platformsMap.get(platformId);
			if (nodes == null) {
				nodes = Collections.EMPTY_LIST;
			}
			return nodes;
		} finally {
			nodeLock.unlock();
		}
	}
	
	@Override
	public String getNodeForIpAddress(String ip) {
		
		Lock nodeLock = nodesLock.readLock();
		nodeLock.lock();
		try {
			RayoNode node = ipsMap.get(ip);
			if (node != null) {
				return node.getHostname();
			}
		} finally {
			nodeLock.unlock();
		}
		return null;
	}
	
	public List<String> getPlatforms() {

		Lock nodeLock = nodesLock.readLock();
		nodeLock.lock();
		try {
			return new ArrayList<String>(platformsMap.keySet());
		} finally {
			nodeLock.unlock();
		}		
	}
	
	@Override
	public GatewayCall storeCall(GatewayCall call) throws DatastoreException {
		
		RayoNode node = getNode(call.getNodeJid());
		if (node == null) {
			throw new RayoNodeNotFoundException();
		}
		Lock callLock = callsLock.writeLock();
		callLock.lock();
		try {
			callsMap.put(call.getCallId(), call);
			addCallToJid(call, call.getClientJid());
			addCallToJid(call, node.getHostname());
		} finally {
			callLock.unlock();
		}
		
		return call;
	}
	
	private void addCallToJid(GatewayCall call, String jid) {
		
		List<GatewayCall> calls = jidsMap.get(jid);
		if (calls == null) {
			calls = new ArrayList<GatewayCall>();
			jidsMap.put(jid, calls);
		}
		if (!calls.contains(call)) {
			calls.add(call);
		}
	}
	
	@Override
	public GatewayCall getCall(String id) {
		
		Lock callLock = callsLock.readLock();
		callLock.lock();
		try {
			return callsMap.get(id);
		} finally {
			callLock.unlock();
		}
	}
	
	@Override
	public GatewayCall removeCall(String id) throws DatastoreException {
				
		Lock callLock = callsLock.writeLock();
		callLock.lock();
		try {
			GatewayCall call = getCall(id);
			callsMap.remove(call.getCallId());
			removeCallFromJid(call, call.getClientJid());
			removeCallFromJid(call, call.getNodeJid());
			
			return call;
		} finally {
			callLock.unlock();
		}
	}
	
	private void removeCallFromJid(GatewayCall call, String jid) {
		
		List<GatewayCall> calls = jidsMap.get(jid);
		if (calls != null) {
			calls.remove(call);
		}
	}
	
	public Collection<String> getCalls(String jid) {
		
		Lock callLock = callsLock.readLock();
		callLock.lock();
		try {
			List<String> ids = new ArrayList<String>();
			List<GatewayCall> calls = jidsMap.get(jid);
			if (calls != null) {
				for(GatewayCall call: calls) {
					ids.add(call.getCallId());
				}
			}
			return ids;
		} finally {
			callLock.unlock();
		}
	}
	
	@Override
	public Collection<String> getCallsForClient(String jid) {

		return getCalls(jid);
	}
	
	@Override
	public Collection<String> getCallsForNode(String jid) {

		return getCalls(jid);
	}
	
	public GatewayClient storeClient(GatewayClient client) throws DatastoreException {
		
		Lock applicationLock = applicationsLock.writeLock();
		applicationLock.lock();
		try {
			clientsMap.put(client.getJid(), client);
			List<String> resources = resourcesMap.get(client.getBareJid());
			if (resources == null) {
				resources = new ArrayList<String>();
				resourcesMap.put(client.getBareJid(), resources);
			}
			if (!resources.contains(client.getResource())) {
				resources.add(client.getResource());
			}
		} finally {
			applicationLock.unlock();
		}
		
		return client;
	}
	
	@Override
	public GatewayClient removeClient(String jid) throws DatastoreException {
				
		Lock applicationLock = applicationsLock.writeLock();
		applicationLock.lock();
		try {
			GatewayClient client = getClient(jid);
			clientsMap.remove(jid);
			List<String> resources = resourcesMap.get(client.getBareJid());
			if (resources != null) {
				resources.remove(client.getResource());
			}
			return client;
		} finally {
			applicationLock.unlock();
		}
	}
	
	@Override
	public GatewayClient getClient(String jid) {
		
		Lock applicationLock = applicationsLock.readLock();
		applicationLock.lock();
		try {
			return clientsMap.get(jid);
		} finally {
			applicationLock.unlock();
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<String> getClientResources(String bareJid) {
		
		Lock applicationLock = applicationsLock.readLock();
		applicationLock.lock();
		try {
			List<String> resources = resourcesMap.get(bareJid);
			if (resources != null) {		
				return Collections.unmodifiableList(resources);
			} else {
				return Collections.EMPTY_LIST;
			}
		} finally {
			applicationLock.unlock();
		}
	}
		
	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public List<String> getClients() {
	
		Lock applicationLock = applicationsLock.readLock();
		applicationLock.lock();
		try {
			Set<String> clients = new HashSet<String>();
			for (String client : clientsMap.keySet()) {
				clients.add(JIDUtils.getBareJid(client));
			}
			
			return new ArrayList(clients);
		} finally {
			applicationLock.unlock();
		}
	}
	
	@Override
	public Application storeApplication(Application application) throws DatastoreException {

		if (getApplication(application.getAppId()) != null) {
			throw new ApplicationAlreadyExistsException();
		}
		
		Lock applicationLock = applicationsLock.writeLock();
		applicationLock.lock();
		try {
			applicationsMap.put(application.getAppId(), application);
		} finally {
			applicationLock.unlock();
		}
		
		return application;
	}
	
	@Override
	public Application getApplication(String id) {

		Lock applicationLock = applicationsLock.readLock();
		applicationLock.lock();
		try {
			return applicationsMap.get(id);
		} finally {
			applicationLock.unlock();
		}
	}
	
	@Override
	public Application removeApplication(String id) throws DatastoreException {

		Application application = getApplication(id);
		if (application == null) {
			throw new ApplicationNotFoundException();
		}
		
		Lock applicationLock = applicationsLock.writeLock();
		applicationLock.lock();
		try {
			applicationsMap.remove(id);
			List<String> addresses = appToAddressesMap.get(id);
			if (addresses != null) {
				for(String address: addresses) {
					addressesMap.remove(address);					
				}
			}
			appToAddressesMap.remove(id);
		} finally {
			applicationLock.unlock();
		}
		
		return application;
	}
	
	@Override
	public Application getApplicationForAddress(String address) {

		Lock applicationLock = applicationsLock.readLock();
		applicationLock.lock();
		try {
			return addressesMap.get(address);
		} finally {
			applicationLock.unlock();
		}
	}

	@Override
	public void storeAddress(String address, String appId) throws DatastoreException {

		List<String> addresses = new ArrayList<String>();
		addresses.add(address);
		storeAddresses(addresses, appId);
	}
	
	@Override
	public void storeAddresses(Collection<String> addresses, String appId) throws DatastoreException {

		Application application = getApplication(appId);
		if (application == null) {
			throw new ApplicationNotFoundException();
		}
		Lock applicationLock = applicationsLock.writeLock();
		applicationLock.lock();
		try {
			for (String address: addresses) {
				addressesMap.put(address, application);
				List<String> addr = appToAddressesMap.get(application.getAppId());
				if (addr == null) {
					addr = new ArrayList<String>();
					appToAddressesMap.put(application.getAppId(), addr);
				}
				if (!addr.contains(address)) {
					addr.add(address);
				}
			}
		} finally {
			applicationLock.unlock();
		}
	}
	
	@Override
	public void removeAddress(String address) throws DatastoreException {

		Lock applicationLock = applicationsLock.writeLock();
		applicationLock.lock();
		try {
			Application application = getApplicationForAddress(address);
			if (application != null) {
				addressesMap.remove(address);
				List<String> addresses = appToAddressesMap.get(application.getAppId());
				if (addresses != null) {
					addresses.remove(address);
					appToAddressesMap.put(application.getAppId(), addresses);
				}
			}
		} finally {
			applicationLock.unlock();
		}		
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<String> getAddressesForApplication(String appId) {

		Lock applicationLock = applicationsLock.readLock();
		applicationLock.lock();
		try {
			List<String> addresses = appToAddressesMap.get(appId);
			if (addresses != null) {
				return new ArrayList<String>(addresses);
			} else {
				return Collections.EMPTY_LIST;
			}
		} finally {
			applicationLock.unlock();
		}
	}
}
