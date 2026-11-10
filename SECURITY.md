# Security policy

This app handles Samsung signing certificates (private keys) and a Samsung account access token, so security problems matter.

## Reporting a vulnerability

Please do **not** open a public issue for a security problem.

Report it privately through GitHub: go to the [Security tab](../../security/advisories/new) and click **Report a vulnerability**.

Include what is affected, how to reproduce it, and what an attacker could do with it. You should get a reply within 7 days.

## Supported versions

Only the latest release gets security fixes.

## Scope

In scope:

- Leaking or mishandling signing certificates, private keys or the Samsung access token
- The local Samsung login receiver (`localhost:4794`) accepting data it should not
- Installing a package on the TV that the user did not choose
- Downloads that skip the SHA-256 check

Out of scope:

- Problems in Samsung's or GitHub's services
- Anything that needs the attacker to already control the phone
