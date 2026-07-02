# TODO

## Combined server auth integration

For the first combined-server implementation, keep clipboard-to-auth validation on HTTP.

Current rule:

- standalone clipboard server uses HTTP to call auth
- combined server also uses HTTP to call auth

Implementation shape for now:

- the combined server runs both route groups in one JVM
- auth routes are exposed from the same process
- clipboard code keeps using the existing HTTP auth client path
- the clipboard module is not refactored yet to depend on a direct auth service interface

Future cleanup:

- extract an auth service interface for clipboard authentication checks
- make the clipboard core depend on that interface
- in standalone deployment, bind that interface to an HTTP-backed implementation
- in combined deployment, bind that interface directly to the auth service implementation used by the auth module

Until that refactor is done, use HTTP in both deployment modes to keep the combined-server feature smaller.

## CLIENTID 
 4. Medium: changing CLIENT_ID, or sharing the same offline file between clients, can
     stop synchronization. Mixed-client data either terminates startup or causes
     endless retries later rather than separating records by owner.

