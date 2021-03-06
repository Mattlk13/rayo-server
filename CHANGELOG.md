# Changelog

## Build 212 / Released 2011-06-19

This build includes CDR creation and delivery over a number of transports, improves statistics, and allows applications to keep Tropo out of the media path. It also includes a number of bug fixes and minor API changes.

### API

* **New**

    * Calls rejected because the server is quiesced now appear in statistics as rejected.

    * Quiesce status is now reported through the admin MBean.

    * Added initial support for CDR management with Spring hooks, CDR manager service, and file-based storage.

    * Ozone Servlet and MohoDriver can now create and store CDRs.

    * CDRs now include `timestamp`, `to`, `from`, `starting time`, `ending time`, and `call status`.

    * CDRs can now be delivered by JMS, XMPP pubsub, AMQP or stored as files.

    * Added ability to alter storage location of CDRs using JMX.

    * Added `media` attribute to Transfer. Possible values are `bridged` (Tropo in media path) or `direct` (Tropo used for signaling only)

* **Changed**

    * If end event generates an error, the error is now handled.

    * API now uses `<presence/>` instead of `<iq/>` for server to client events.

* **Fixed**

    * Transfer broke in last build.

    * Timeouts were being parsed in 'PT' time format instead of milliseconds.

    * Dial threw `java.lang.IllegalArgumentException: Call id cannot be null`; now it doesn't.

    * Redirect caused an error/

    * If verb encountered an error, the `complete` event wasn't getting set.

## Build 188 / Released 2011-06-10

This release improves consistency between API elements, makes it easier to work with complete events, simplifies the API by reducing unnecessary element nesting, adds some future-proofing to naming and call IDs, and also adds management and monitoring of cluster status through HTTP and JMX APIs.

### API

* **New**

    * `Complete` events now include one of the following reasons: `stop`, `hangup`, or `error`.

    * Now validating that choices is present and is either a URL or inline choices, but not both.

    * Validation errors return `<end/>` element

    * Added `CallRegistry` method to return all active calls

* **Changed**

    * Complete events are part of the Ozone namespace, allowing the event to be read without needing to load a new namespace.

    * The "reason" for End is now a nested element instead of an attribute. `<end xmlns='urn:xmpp:ozone:1'><timeout /></end>` instead of `<end xmlns='urn:xmpp:ozone:1' reason='timeout'/>`

    * For the `mode` attribute of `ask`, `both` has been changed to `any`. Valid values are now `dtmf`, `voice`, or `any`.

    * `Ask`, `conference`, `transfer`, and `say` no longer have a `voice` attribute. Voice can instead be set in the SSML for the prompt contained in these elements.

    * When making outbound calls, the call ID in the IQ result to dial is no longer a full JID, just the local portion of the ID. Including the JID made the call ID be specific to an XMPP transport. In the future, other transports may be used.

    * Outbound call progress events changed from `info/ring` and `info/answer` to root elements named `ringing` and `answered`.

    * Timeouts are now in milliseconds (previously they were in PT format)

* **Fixed**

    * `say`, `ask` and `transfer` now properly report the reason 'HANGUP' when forced to terminate due to far end disconnect.

    * Conference could lead to deadlocks; now it can't.

    * EndEvent didn't fire if the call with active verbs disconnected AND the handler's stop method fired a complete event on the same thread as the one calling stop

    * Validation of `from` on outbound call was missing the error message

### Management & Monitoring

* **New**

    * Added HTTP and JMX interfaces for retrieving monitoring info.

    * You can now quiesce a server through HTTP or JMX.

    * You can now change logging level on running servers through JMX.

    * Added call statistics for monitoring: total calls, outgoing calls, incoming calls, rejected, redirected, busy, timed out, failed, accepted, answered, and the count of active verbs

    * Added Ozone message statistics for monitoring different message types, iq, call events, validation errors, all commands, presence and message stanzas received.

    * Monitoring data includes build version information, quiesce status.

    * New CDRs: available via file storage and amqp

Go [here](https://github.com/tropo/tropo2/wiki/Tropo-2-Monitoring) for detailed info about Management & Monitoring.