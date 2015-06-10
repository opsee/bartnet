The Streaming Websocket Protocol
======================

The websocket endpoint in bartnet allows access to a customer's event stream.  Websocket
connections must be authorized using a valid HMAC obtained from the authorize endpoint.
Once authorized, the websocket client can manage its topic subscriptions.

Message Format
-----------

Communication over the websocket happens via JSON encoded messages which have a standard
set of fields:

```
{
    "id": 12345678, //message id, should be unique for this session
    "version": 1, //versioning of the protocol so we don't break stuff
    "command": "authenticate",
    "sent": 12345678, //timestamp of when this message was sent
    "attributes": {"HMAC" : "1--iFplvAUtzi_veq_dMKPfnjtg_SQ="}, //free form map of strings
    "in_reply_to": 1, //if this message is a reply, marks the ID we are replying to
    "customer_id": "abc1234",
    "instance_id": "i-334345", //if this message is from a bastion this will be its aws id
    "host": "ec2-255-255-255-255.compute-1.amazonaws.com", //the hostname of the checked resource if applicable
    "service": "sg-12345", //the name of the service group being checked
    "state": "up", //the service state if this is a check result
    "time": 12345678, //timestamp of when the check ran if applicable
    "description": "", //an optional human readable description
    "tags": ["dev"], //a list of free form tags
    "metric": 0.0, //whatever got measured during the check. could be latency, etc
    "ttl": 3.5 //the TTL dictates how long this event remains valid
}
```

Authentication
-----------

When a websocket first connects it must authenticate itself by presenting the HMAC that it
obtained from the authenticate REST endpoint.  Once a websocket session is authenticated
it can obtain access to all of the topics for that customer.

```
REQUEST

{
    "id": 1,
    "version": 1,
    "command": "authenticate",
    "sent": 12345678,
    "attributes": {"HMAC" : "1--iFplvAUtzi_veq_dMKPfnjtg_SQ="}
}

RESPONSE

{
    "id": 2,
    "version": 1,
    "command": "authenticate",
    "sent": 12345678,
    "in_reply_to": 1,
    "attributes": {"authenticated": "customer_id"}
}
```
 
Subscriptions
------------

There are a standard set of topics available for every customer.  Typically subscriptions
can simply reference the topic name, however at some point in the future ws clients will
have the ability to subscribe to multiple customer feeds so long as it is authenticated.

```
REQUEST

{
    "id": 3,
    "version": 1,
    "command": "subscribe",
    "attributes": {
        "subscribe_to": "discovery",
        "unsubscribe_from": "connected"
    }
}

RESPONSE

{
    "id": 4,
    "version": 1,
    "command": "subscribe",
     "attributes": {
        "subscribed_to": "discovery,check_results"
     }
}
```