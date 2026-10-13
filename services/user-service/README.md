# user-service

Profiles, KYC tiers, linked bank accounts and saved beneficiaries. Port **8082**, database `user_db`.

## Features

| Feature | Details |
|---|---|
| Profile | Created from `auth.user-registered` (idempotent consumer), or lazily on first access. Name, e-mail (encrypted) and date of birth. Name and DOB are locked after KYC |
| KYC tiers | `NONE` → `MIN_KYC` (PAN / voter ID / driving licence / passport, plus name and DOB, age 18+) → `FULL_KYC` (Aadhaar with Verhoeff checksum + document upload). One step at a time. Publishes `user.kyc-upgraded` |
| **MOCK KYC provider** | `MockKycVerifier` approves every well-formed document **except numbers containing `9999`**, so you can test rejections (e.g. `ABCDE9999F`). Rejections are recorded in the KYC history |
| **MOCK document storage** | `MockDocumentStorage` keeps a reference and the SHA-256 of the upload but discards the bytes (no PII files on laptops). Uploads must be JPEG, PNG or PDF up to 5 MB; checked by magic bytes, not the declared type |
| One document, one account | A verified document number can't verify a second account (`USER_KYC_DOCUMENT_IN_USE`) |
| Bank accounts | Up to 5, unique per user. The first becomes primary; removing the primary promotes the next. Numbers are AES-GCM encrypted at rest, deduplicated via a blind index, and only ever returned masked (`XXXXXXXX9012`). Publishes `user.bank-account-linked` |
| Beneficiaries | Up to 50 saved payees: `MOBILE`, `VPA` or `BANK_ACCOUNT` + IFSC. Values are encrypted and returned masked |

## Package structure

```
com.payverse.user
├── config      UserConfig (Clock)
├── controller  ProfileController, KycController, BankAccountController, BeneficiaryController, InternalUserController
├── dto         request/response records
├── entity      UserProfile, KycRecord, BankAccount, Beneficiary + enums (KycTier, IdDocumentType, …)
├── repository  Spring Data repositories
├── service     ProfileService, KycService, BankAccountService, BeneficiaryService, UserEventPublisher,
│               KycVerifier + MockKycVerifier, DocumentStorage + MockDocumentStorage
├── mapper      UserMapper (MapStruct entity → DTO)
├── messaging   UserRegisteredListener (Kafka)
├── exception   UserErrorCode
└── model       IdDocument, Verhoeff
```

## API

| Method | Path | Auth |
|---|---|---|
| GET / PUT | `/api/v1/users/me` | user |
| GET | `/api/v1/users/me/kyc` | user |
| POST | `/api/v1/users/me/kyc/min` | user |
| POST (multipart) | `/api/v1/users/me/kyc/full` (`aadhaarNumber`, `document`) | user |
| GET / POST | `/api/v1/users/me/bank-accounts` | user |
| POST | `/api/v1/users/me/bank-accounts/{id}/primary` | user |
| DELETE | `/api/v1/users/me/bank-accounts/{id}` | user |
| GET / POST / DELETE | `/api/v1/users/me/beneficiaries[/{id}]` | user |
| GET | `/internal/users/{userId}` | SERVICE |
| GET | `/internal/users/{userId}/bank-accounts/{accountId}` | SERVICE |

Swagger UI: http://localhost:8082/swagger-ui.html. Requests: [../../http/02-user.http](../../http/02-user.http).

## Events

Consumes `auth.user-registered` (consumer `user-service.profile-on-registration`, de-duplicated via `processed_events`).
Publishes `user.kyc-upgraded` and `user.bank-account-linked`.

## Error codes

`USER_PROFILE_NOT_FOUND`, `USER_INVALID_PROFILE`, `USER_KYC_INVALID_DOCUMENT`, `USER_KYC_TIER_TRANSITION`,
`USER_KYC_VERIFICATION_FAILED`, `USER_KYC_DOCUMENT_IN_USE`, `USER_KYC_DOCUMENT_TOO_LARGE`, `USER_KYC_UNSUPPORTED_FILE`,
`USER_BANK_ACCOUNT_INVALID`, `USER_BANK_ACCOUNT_DUPLICATE`, `USER_BANK_ACCOUNT_LIMIT`, `USER_BANK_ACCOUNT_NOT_FOUND`,
`USER_BENEFICIARY_INVALID`, `USER_BENEFICIARY_DUPLICATE`, `USER_BENEFICIARY_LIMIT`, `USER_BENEFICIARY_NOT_FOUND`.

## Run and test

```bash
make infra-up && make run-auth-service && make run-user-service
./mvnw -pl services/user-service -am verify
```

`UserServiceIT` covers event-driven profile creation (with a duplicate delivery), encryption at rest,
both KYC upgrades and rejection, document reuse, upload validation, bank account primary rules, masking,
events, beneficiaries, and the SERVICE-only internal API.
