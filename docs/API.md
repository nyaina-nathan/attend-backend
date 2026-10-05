# Events & Tickets API: Spec

Base URL: `/api`
Format: JSON (`Content-Type: application/json`), UTF-8.

## Conventions

- **IDs**: UUID strings.
- **Timestamps**: ISO-8601 UTC, e.g. `2026-11-20T18:00:00Z`.
- **Money**: decimal as a string-safe number with 2 decimals (`"price": 25.00`). Currency is not in the schema, so assume a single currency for now.
- **Auth**: JWT access token + refresh token, both sent as `HttpOnly` cookies (`access_token`, `refresh_token`). Endpoints marked 🔒 require the access cookie.
- **Never returned**: `password_hash`, `token_hash`.
- **Pagination** (list endpoints): query `?page=0&size=20`, response wrapper:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

- **Error format** (all errors):

```json
{
  "timestamp": "2026-10-05T10:00:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Validation failed",
  "path": "/api/events",
  "details": [
    { "field": "endTime", "message": "must be after startTime" }
  ]
}
```

| Status | Meaning |
|---|---|
| 400 | Validation error / malformed body |
| 401 | Missing, expired or invalid access token |
| 403 | Authenticated but not allowed (e.g. not an organizer) |
| 404 | Resource not found (also used to hide private events) |
| 409 | Conflict (duplicate email/username, sold out, ticket already used) |

---

## Authorization model

Roles are **per event**, not global. A user can be `ADMIN` of one event and a plain customer of another, so the JWT carries only the user identity (no role claims) and role checks happen in the service layer.

### Security config (coarse, URL-level)
Only answers "is the caller authenticated?":
- Public: `/auth/register`, `/auth/login`, `/auth/refresh`, `GET /events`, `GET /events/{eventId}` (visibility rules still apply in the service), `GET /events/{eventId}/tiers`.
- Everything else: authenticated.
- No role checks here.

### Service layer (fine-grained, per event)
Every organizer-only operation starts by loading the caller's `event_organizer` row for that event. The logic lives in a single component so it is not duplicated across services:

```java
@Component
@RequiredArgsConstructor
public class EventAccess {
    private final EventOrganizerRepository organizers;

    public void requireOrganizer(UUID eventId, UUID userId) {
        if (!organizers.existsByIdEventAndIdUser(eventId, userId))
            throw new ForbiddenException("NOT_AN_ORGANIZER");
    }

    public void requireCreator(UUID eventId, UUID userId) {
        if (!organizers.existsByIdEventAndIdUserAndEventRole(eventId, userId, EventRole.CREATOR))
            throw new ForbiddenException("NOT_THE_CREATOR");
    }
}
```

Each service method then begins with one line, e.g. `eventAccess.requireOrganizer(eventId, currentUserId)`.

### Resolving the event id
Endpoints that don't carry the event id in the path must resolve it before the check:

| Endpoint | Resolution | Required role |
|---|---|---|
| `PATCH/DELETE /tiers/{tierId}` | tier → `id_event` | organizer |
| `POST /tickets/{ticketId}/check-in` | ticket → tier → `id_event` | organizer |
| `POST /tickets/{ticketId}/refund` | ticket → tier → `id_event` | organizer |
| `GET /tickets/{ticketId}` | owner **or** organizer of ticket → tier → `id_event` | owner / organizer |

Check-in is the critical one: a scanner who is an organizer of event A must never be able to scan a ticket belonging to event B. Always compare against the event resolved from the **ticket**, never an event id supplied by the client.

### 403 vs 404
- Caller is authenticated but not allowed on a **visible** resource: `403`.
- Private event and caller is neither organizer nor ticket holder: `404`, so the event's existence is not leaked.

---

## 1. Auth

### POST `/auth/register`
Public. Creates a user and logs them in (sets cookies).

Request:
```json
{
  "email": "jane@example.com",
  "password": "S3cretPass!",
  "fullName": "Jane Doe",
  "username": "janedoe",
  "dateBirth": "2000-05-14"
}
```
`dateBirth` is optional.

Response `201`:
```json
{
  "id": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10",
  "email": "jane@example.com",
  "fullName": "Jane Doe",
  "username": "janedoe",
  "dateBirth": "2000-05-14",
  "createdAt": "2026-10-05T10:00:00Z"
}
```
Errors: `400`, `409` (`EMAIL_TAKEN` / `USERNAME_TAKEN`).

### POST `/auth/login`
Public.

Request:
```json
{
  "email": "jane@example.com",
  "password": "S3cretPass!"
}
```
Response `200`: same body as the user object above. Sets `access_token` and `refresh_token` cookies.
Errors: `401` (`INVALID_CREDENTIALS`).

### POST `/auth/refresh`
Public (needs the `refresh_token` cookie). No body.
Rotates the token: old one gets `revoked_at` + `replaced_by`. If a revoked token is presented again, the whole chain is revoked.

Response `204` (new cookies set).
Errors: `401` (`REFRESH_TOKEN_INVALID` / `REFRESH_TOKEN_REUSED`).

### POST `/auth/logout` 🔒
Revokes the current refresh token and clears cookies. No body.
Response `204`.

---

## 2. Users

### GET `/users/me` 🔒
Response `200`:
```json
{
  "id": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10",
  "email": "jane@example.com",
  "fullName": "Jane Doe",
  "username": "janedoe",
  "dateBirth": "2000-05-14",
  "createdAt": "2026-10-05T10:00:00Z",
  "updatedAt": "2026-10-05T10:00:00Z"
}
```

### PATCH `/users/me` 🔒
Partial update. All fields optional.

Request:
```json
{
  "fullName": "Jane D.",
  "username": "jane_d",
  "dateBirth": "2000-05-14"
}
```
Response `200`: updated user object. Errors: `400`, `409`.

### POST `/users/me/password` 🔒
Request:
```json
{
  "currentPassword": "S3cretPass!",
  "newPassword": "An0therPass!"
}
```
Response `204`. Revokes all other refresh tokens. Errors: `400`, `401`.

---

## 3. Events

`status`: `OK` | `CANCELLED` | `POSTPONED`

### POST `/events` 🔒
Creates an event; the caller is automatically added to `event_organizer` as `CREATOR`.

Request:
```json
{
  "title": "Tana Jazz Night",
  "description": "An evening of live jazz.",
  "startTime": "2026-11-20T18:00:00Z",
  "endTime": "2026-11-20T22:00:00Z",
  "isPublic": true,
  "location": "Antananarivo, Alliance Française"
}
```
`description` and `location` optional; `isPublic` defaults to `true`.

Response `201`:
```json
{
  "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
  "title": "Tana Jazz Night",
  "description": "An evening of live jazz.",
  "startTime": "2026-11-20T18:00:00Z",
  "endTime": "2026-11-20T22:00:00Z",
  "isPublic": true,
  "location": "Antananarivo, Alliance Française",
  "status": "OK",
  "myRole": "CREATOR",
  "createdAt": "2026-10-05T10:00:00Z",
  "updatedAt": "2026-10-05T10:00:00Z"
}
```
Errors: `400` (e.g. `endTime` must be after `startTime`).

### GET `/events`
Public. Lists events where `isPublic = true`.

Query params: `q` (title search), `from`, `to` (filter on `startTime`), `status` (default `OK`), `page`, `size`, `sort` (default `startTime,asc`).

Response `200`: paginated wrapper with items:
```json
{
  "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
  "title": "Tana Jazz Night",
  "startTime": "2026-11-20T18:00:00Z",
  "endTime": "2026-11-20T22:00:00Z",
  "location": "Antananarivo, Alliance Française",
  "status": "OK",
  "minPrice": 0.00
}
```

### GET `/events/{eventId}`
Public for public events. Private events are visible only to organizers and ticket holders (otherwise `404`).

Response `200`:
```json
{
  "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
  "title": "Tana Jazz Night",
  "description": "An evening of live jazz.",
  "startTime": "2026-11-20T18:00:00Z",
  "endTime": "2026-11-20T22:00:00Z",
  "isPublic": true,
  "location": "Antananarivo, Alliance Française",
  "status": "OK",
  "myRole": null,
  "tiers": [
    {
      "id": "a1b2c3d4-0000-4000-8000-000000000001",
      "tierName": "Standard",
      "price": 10.00,
      "capacity": 200,
      "remaining": 143
    }
  ],
  "createdAt": "2026-10-05T10:00:00Z",
  "updatedAt": "2026-10-05T10:00:00Z"
}
```
`myRole` is `CREATOR`, `ADMIN`, or `null` (anonymous / not an organizer).
`remaining` = `capacity` minus tickets in `VALID` or `USED`.

### PATCH `/events/{eventId}` 🔒
Organizers only (`CREATOR` or `ADMIN`). Partial update.

Request:
```json
{
  "title": "Tana Jazz Night 2",
  "description": "Updated description",
  "startTime": "2026-11-21T18:00:00Z",
  "endTime": "2026-11-21T22:00:00Z",
  "isPublic": false,
  "location": "New venue",
  "status": "POSTPONED"
}
```
Response `200`: full event object. Errors: `400`, `403`, `404`.

### POST `/events/{eventId}/cancel` 🔒
Organizers only. Sets `status = CANCELLED`. No body.
Response `200`: event object.
Side effect to decide: tickets stay `VALID` until refunded, or are bulk-marked `REFUNDED`. See notes at the end.

### DELETE `/events/{eventId}` 🔒
`CREATOR` only. Allowed only if no tickets exist; otherwise `409` (use cancel instead).
Response `204`.

---

## 4. Event organizers

### GET `/events/{eventId}/organizers` 🔒
Organizers only.

Response `200`:
```json
[
  {
    "id": "c0ffee00-0000-4000-8000-000000000001",
    "userId": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10",
    "username": "janedoe",
    "fullName": "Jane Doe",
    "eventRole": "CREATOR"
  }
]
```

### POST `/events/{eventId}/organizers` 🔒
`CREATOR` only. Adds an `ADMIN`.

Request:
```json
{
  "username": "johnsmith"
}
```
(Alternative: `"email"`.) Response `201`: organizer object with `"eventRole": "ADMIN"`.
Errors: `404` (user not found), `409` (already an organizer).

### DELETE `/events/{eventId}/organizers/{organizerId}` 🔒
`CREATOR` only. The `CREATOR` row cannot be removed (`409`).
Response `204`.

---

## 5. Ticket tiers

### GET `/events/{eventId}/tiers`
Same visibility rules as the event.

Response `200`:
```json
[
  {
    "id": "a1b2c3d4-0000-4000-8000-000000000001",
    "eventId": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
    "tierName": "VIP",
    "price": 40.00,
    "capacity": 50,
    "remaining": 12
  }
]
```

### POST `/events/{eventId}/tiers` 🔒
Organizers only.

Request:
```json
{
  "tierName": "VIP",
  "price": 40.00,
  "capacity": 50
}
```
Response `201`: tier object. Errors: `400` (`price >= 0`, `capacity >= 0`), `409` (tier name already used for this event).

### PATCH `/tiers/{tierId}` 🔒
Organizers only.

Request:
```json
{
  "tierName": "VIP Plus",
  "price": 45.00,
  "capacity": 60
}
```
Response `200`: tier object.
Rules: `capacity` cannot go below the number of tickets already issued (`409`). Price changes don't affect existing tickets (`price_paid` is frozen).

### DELETE `/tiers/{tierId}` 🔒
Organizers only. Only if no tickets exist for the tier (`409` otherwise).
Response `204`.

---

## 6. Tickets

`ticketStatus`: `VALID` | `USED` | `CANCELLED` | `REFUNDED`
The QR code encodes the ticket `id` only.

### POST `/tiers/{tierId}/tickets` 🔒
Issues a ticket to the caller. Runs in one transaction with a row lock on the tier (no overselling).

Request: empty body (or `{}`).

Response `201`:
```json
{
  "id": "7d9e1f2a-4b3c-4d5e-8f6a-1b2c3d4e5f60",
  "event": {
    "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
    "title": "Tana Jazz Night",
    "startTime": "2026-11-20T18:00:00Z",
    "location": "Antananarivo, Alliance Française"
  },
  "tier": {
    "id": "a1b2c3d4-0000-4000-8000-000000000001",
    "tierName": "VIP"
  },
  "pricePaid": 40.00,
  "ticketStatus": "VALID",
  "checkedInAt": null,
  "validUntil": "2026-11-20T22:00:00Z",
  "createdAt": "2026-10-05T10:00:00Z"
}
```
Errors: `404`, `409` (`SOLD_OUT`, `EVENT_NOT_OPEN` when the event is cancelled or ended).

### GET `/tickets/me` 🔒
Caller's tickets.

Query params: `status`, `upcoming` (boolean), `page`, `size`.
Response `200`: paginated wrapper of the ticket object above.

### GET `/tickets/{ticketId}` 🔒
Allowed for the owner or any organizer of the ticket's event.
Response `200`: ticket object, plus for organizers:
```json
{
  "owner": { "id": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10", "fullName": "Jane Doe", "username": "janedoe" },
  "checkedBy": null
}
```

### POST `/tickets/{ticketId}/check-in` 🔒
Organizers of the ticket's event only. Called after scanning the QR code. Atomic: `UPDATE ... WHERE ticket_status = 'VALID'`.

Request: empty body.

Response `200`:
```json
{
  "id": "7d9e1f2a-4b3c-4d5e-8f6a-1b2c3d4e5f60",
  "ticketStatus": "USED",
  "checkedInAt": "2026-11-20T18:05:12Z",
  "checkedBy": { "id": "9a8b7c6d-0000-4000-8000-000000000009", "username": "scanner1" },
  "owner": { "fullName": "Jane Doe", "username": "janedoe" },
  "tier": { "tierName": "VIP" }
}
```
Errors:

| Status | `error` code | Case |
|---|---|---|
| 403 | `NOT_AN_ORGANIZER` | Scanner is not an organizer of this event |
| 404 | `TICKET_NOT_FOUND` | Unknown ticket id |
| 409 | `TICKET_ALREADY_USED` | Includes `checkedInAt` in the error `details` |
| 409 | `TICKET_NOT_VALID` | Cancelled or refunded |
| 409 | `TICKET_EXPIRED` | Past `validUntil` |

### POST `/tickets/{ticketId}/cancel` 🔒
Owner cancels their own `VALID` ticket (frees capacity).
Request: empty body. Response `200`: ticket object with `"ticketStatus": "CANCELLED"`.
Errors: `409` (already used / not valid).

### POST `/tickets/{ticketId}/refund` 🔒
Organizers only. Marks a ticket `REFUNDED`.
Request:
```json
{
  "reason": "Event cancelled"
}
```
(`reason` is optional and is not stored by the current schema.) Response `200`: ticket object.

### GET `/events/{eventId}/tickets` 🔒
Organizers only. Attendee list.

Query params: `status`, `tierId`, `q` (name/username), `page`, `size`.
Response `200`: paginated wrapper of:
```json
{
  "id": "7d9e1f2a-4b3c-4d5e-8f6a-1b2c3d4e5f60",
  "owner": { "id": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10", "fullName": "Jane Doe", "username": "janedoe" },
  "tier": { "id": "a1b2c3d4-0000-4000-8000-000000000001", "tierName": "VIP" },
  "pricePaid": 40.00,
  "ticketStatus": "USED",
  "checkedInAt": "2026-11-20T18:05:12Z",
  "createdAt": "2026-10-05T10:00:00Z"
}
```

### GET `/events/{eventId}/stats` 🔒
Organizers only.

Response `200`:
```json
{
  "eventId": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
  "totalCapacity": 250,
  "ticketsIssued": 107,
  "ticketsCheckedIn": 64,
  "revenue": 1850.00,
  "byTier": [
    { "tierId": "a1b2c3d4-0000-4000-8000-000000000001", "tierName": "Standard", "issued": 57, "checkedIn": 30, "revenue": 570.00 }
  ]
}
```

---

## 7. Endpoint summary

| Method | Path | Auth | Who |
|---|---|---|---|
| POST | `/auth/register` | none | anyone |
| POST | `/auth/login` | none | anyone |
| POST | `/auth/refresh` | refresh cookie | anyone |
| POST | `/auth/logout` | 🔒 | user |
| GET / PATCH | `/users/me` | 🔒 | user |
| POST | `/users/me/password` | 🔒 | user |
| POST | `/events` | 🔒 | user |
| GET | `/events` | none | anyone |
| GET | `/events/{eventId}` | optional | public, or organizer / ticket holder |
| PATCH | `/events/{eventId}` | 🔒 | organizer |
| POST | `/events/{eventId}/cancel` | 🔒 | organizer |
| DELETE | `/events/{eventId}` | 🔒 | creator |
| GET / POST | `/events/{eventId}/organizers` | 🔒 | organizer / creator |
| DELETE | `/events/{eventId}/organizers/{organizerId}` | 🔒 | creator |
| GET | `/events/{eventId}/tiers` | optional | same as event |
| POST | `/events/{eventId}/tiers` | 🔒 | organizer |
| PATCH / DELETE | `/tiers/{tierId}` | 🔒 | organizer |
| POST | `/tiers/{tierId}/tickets` | 🔒 | user |
| GET | `/tickets/me` | 🔒 | user |
| GET | `/tickets/{ticketId}` | 🔒 | owner / organizer |
| POST | `/tickets/{ticketId}/check-in` | 🔒 | organizer |
| POST | `/tickets/{ticketId}/cancel` | 🔒 | owner |
| POST | `/tickets/{ticketId}/refund` | 🔒 | organizer |
| GET | `/events/{eventId}/tickets` | 🔒 | organizer |
| GET | `/events/{eventId}/stats` | 🔒 | organizer |

---

## Notes and open decisions

- **Payments**: the schema has `price_paid` but no payment table. For paid tiers, `POST /tiers/{tierId}/tickets` currently issues the ticket directly. If you add real payments, this endpoint should become a checkout flow (create a pending order, then confirm via webhook).
- **Event cancellation**: decide whether `cancel` bulk-updates tickets to `REFUNDED` or leaves them `VALID`. The check-in endpoint should reject tickets of a `CANCELLED` event either way.
- **`validUntil`**: nullable in the schema. A sensible default is the event's `endTime` at issue time.
- **Postponed events**: if `startTime`/`endTime` change, existing tickets' `validUntil` should be updated too.
- **Private events**: there is no invite mechanism in the schema, so access is by direct link (UUID) for ticket purchase unless you add an invite table.