package com.tropo.ozone.gateway;

import java.util.Collection;

import com.voxeo.servlet.xmpp.JID;

public interface TropoAppService {
	JID lookup (JID jid);
	void add (JID jid) throws UnknownApplicationException;
	void remove (JID jid);
	Collection<JID> lookupAll (JID jid);
	Collection<JID> lookupAll ();
	int getPPID (JID jid);
}