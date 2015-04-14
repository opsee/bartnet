# API
## Signups Endpoint

###**/signups**

####Post

Post adds a new signup to the beta list. The POST verb of signups is publicly accessible without authentication. Upon successful creation of a new signup the endpoint will return a status code of 201. If the email field is a duplicate of an existing signup then no new signup will be created and the endpoint will return a 200 status code. 

```
curl -X POST -H 'Content-Type: application/json' -d '{"name":"cliff","email":"cliff+signup@leaninto.it"}' http://api-beta.opsee.co/signups
```

####Get

Get will return a list of signups. The GET verb requires authentication with a super-user enabled auth token. The endpoint will only return a list of 100 signups at a time, ordered by email address. The URL parameter `page` can be used to control pagination. Page count starts at 1.

```
curl -H 'Authorization: HMAC 1--2jmj7l5rSw0yVb_vlWAYkK_YBwk=' http://api-beta.opsee.co/signups
```

###**/authenticate/password**

####POST

Posting to the password authentication endpoint generates an HMAC token that can subsequently be used in the authorization header for endpoints which require it. Superuser status is intrinsic to the login that generates an HMAC, not the HMAC itself.

```
curl -X POST -H 'Content-Type: application/json' -d '{"email":"cliff@leaninto.it","password":"cliff"}' http://api-beta.opsee.co/authenticate/password
```
