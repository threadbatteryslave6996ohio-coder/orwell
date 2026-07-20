# TODO

## CLIENTID 
 4. Medium: changing CLIENT_ID, or sharing the same offline file between clients, can
     stop synchronization. Mixed-client data either terminates startup or causes
     endless retries later rather than separating records by owner.

## Logging
 - Add logging to the mac client (`clients/mac`) and ship the logs to the remote
     server, the way the servers already do — route through the shared `Logger`
     (`packages/logger`) and push to Loki rather than only writing locally.
