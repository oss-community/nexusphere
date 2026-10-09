# <p align="center">Nexusphere Ledger Mandate</p>

<p align="center">Mandates and the formats two organizations exchange, with a verifier SDK.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)

## Purpose

<p style="text-align: justify;">

A grant lives inside one ledger. When an agent acts at another organization, that organization needs a signed,
self-contained proof of what the agent may do, and a way to check it without trusting the sender. This library is that
proof, its revocation list and the verifier the receiving side uses.

</p>

## Responsibilities

* Compact JWS signing and parsing with EdDSA (`Jws`) and Ed25519 keys as JWK (`Jwk`)
* `MandateClaims` and `Mandates`: the mandate issued from a grant as an SD-JWT VC, and `SdJwt` to parse it, check
  its disclosures and present it with fewer of them
* `StatusList`: the signed revocation list in the IETF Token Status List format
* `MandateVerifier`: trusted issuers, keys from the issuer's JWKS, signature, time, audience, coverage and revocation,
  failing closed
* `ExchangeRequest` and `ExchangeReceipt`: the signed request proof and receipt of an A2A exchange between two ledgers

## Dependencies

| Depends on | Used by              |
|------------|----------------------|
| `chain`    | `server`, `verifier` |

##

**<p align="center">[Top](#nexusphere-ledger-mandate)</p>**
