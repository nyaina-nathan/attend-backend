# Events & Tickets API: Spec

Base URL: `/api`
Format: JSON (`Content-Type: application/json`), UTF-8.

## Conventions

- **IDs**: UUID strings.
- **Timestamps**: ISO-8601 UTC, e.g. `2026-11-20T18:00:00Z`.
- **Money**: decimal as a string-safe number with 2 decimals (`"price": 25.00`). Each event carries a `priceUnit` (a free-form currency/unit code, default `USD`) that applies to every amount on that event: its tiers' `price`, `minPrice`, ticket `pricePaid`, and `revenue`. It is **not converted** when changed — it only relabels the stored numbers. Aggregate dashboard `revenue` spans events that may use different units, so it is unit-less.
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
- Public: `/auth/register`, `/auth/login`, `/auth/refresh`, `GET /events`, `GET /events/{eventId}` (visibility rules still apply in the service), `GET /events/{eventId}/tiers`. `GET /events` stays public; its `isMine` filter only takes effect for an authenticated caller.
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

### 401, 403 vs 404
- Private event and caller is anonymous: `401`.
- Private event and caller is authenticated but not an organizer: `403 NOT_AN_ORGANIZER`.
- Unknown resource id: `404`.

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

### GET `/users` 🔒
Searches users by a case-insensitive partial `username` and/or `email`. At least one of the two
query parameters is required; when both are given, users matching either are returned. Paginated.

Query parameters:
- `username` — optional partial match on the username.
- `email` — optional partial match on the email.
- `page` (default `0`), `size` (default `20`, 1..100), `sort` (default `username,asc`; sortable
  fields: `username`, `fullName`, `createdAt`).

Response `200`:
```json
{
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "content": [
    {
      "id": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10",
      "email": "jane@example.com",
      "fullName": "Jane Doe",
      "username": "janedoe",
      "dateBirth": "2000-05-14",
      "createdAt": "2026-10-05T10:00:00Z"
    }
  ]
}
```
Errors: `400` (neither `username` nor `email`, or invalid `page`/`size`/`sort`), `401`.

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
Response `204`. Revokes all existing refresh tokens and issues a new session for the caller (new cookies set); other devices are logged out. Errors: `400`, `401`.

### DELETE `/users/me` 🔒
Permanently deletes the authenticated user and all related data, irreversibly, and clears the auth cookies.

Removes the user's refresh tokens, `event_organizer` rows and owned tickets. Events they created are deleted too, including their tiers and tickets held by others. Tickets they checked in keep their status with their check-in marker cleared (`checked_by` and `checkedInAt`).

Response `204`. Errors: `401`.

### GET `/users/me/dashboard` 🔒
Aggregated data for the dashboard home page, in a single call. Covers the caller's organizer
activity and their attendee activity. The month calendar is not part of it: use
`GET /events?isMine=true&from=&to=` for that. The response is per-user and is returned with
`Cache-Control: no-store`.

Definitions used below:
- **Upcoming event**: `endTime > now` and `status` is `OK` or `POSTPONED`. An event that is
  currently happening still counts as upcoming.
- **Counted tickets**: tickets in `VALID` or `USED` (same rule as `remaining`). `CANCELLED` and
  `REFUNDED` tickets are ignored.
- **Organizing**: events where the caller is `CREATOR` or `ADMIN`.

Response `200`:
```json
{
  "organizing": {
    "eventsTotal": 12,
    "eventsUpcoming": 3,
    "ticketsSold": 214,
    "revenue": 3450.00,
    "nextEvent": {
      "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
      "title": "Tana Jazz Night",
      "startTime": "2026-11-20T18:00:00Z",
      "endTime": "2026-11-20T22:00:00Z",
      "location": "Antananarivo, Alliance Française",
      "priceUnit": "USD",
      "status": "OK",
      "myRole": "CREATOR",
      "totalCapacity": 250,
      "ticketsIssued": 107,
      "ticketsCheckedIn": 0
    },
    "attention": [
      {
        "type": "NO_TIERS",
        "eventId": "b7a1d2c3-0000-4000-8000-000000000002",
        "eventTitle": "Open Mic",
        "startTime": "2026-11-12T17:00:00Z"
      },
      {
        "type": "LOW_STOCK",
        "eventId": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
        "eventTitle": "Tana Jazz Night",
        "startTime": "2026-11-20T18:00:00Z",
        "tierId": "a1b2c3d4-0000-4000-8000-000000000001",
        "tierName": "VIP",
        "remaining": 3
      }
    ]
  },
  "attending": {
    "ticketsUpcoming": 2,
    "nextTicket": {
      "id": "7d9e1f2a-4b3c-4d5e-8f6a-1b2c3d4e5f60",
      "event": {
        "id": "c4d5e6f7-0000-4000-8000-000000000003",
        "title": "Antsirabe Food Fair",
        "startTime": "2026-12-05T09:00:00Z",
        "location": "Antsirabe",
        "priceUnit": "USD"
      },
      "tier": { "id": "a1b2c3d4-0000-4000-8000-000000000009", "tierName": "Standard" },
      "ticketStatus": "VALID"
    }
  }
}
```

Fields:
- `organizing.eventsTotal`: all events the caller organizes, whatever their status.
- `organizing.eventsUpcoming`: count of upcoming events.
- `organizing.ticketsSold` and `revenue`: counted tickets and the sum of `price_paid`, **scoped
  to upcoming events only**. `revenue` is unit-less: it sums events that may use different
  `priceUnit` values.
- `organizing.nextEvent`: the upcoming event with the earliest `startTime`, or `null` if none.
  The counters use the same rules as `GET /events/{eventId}/stats`; its `priceUnit` is that
  event's currency.
- `organizing.attention`: at most 5 items, ordered by `startTime` ascending. Each item has a
  `type`:

  | `type` | Condition | Extra fields |
  |---|---|---|
  | `NO_TIERS` | Upcoming `OK` event starting within 7 days with no tiers | none |
  | `LOW_STOCK` | A tier of an upcoming `OK` event with `remaining <= 10` and `remaining > 0` | `tierId`, `tierName`, `remaining` |
  | `POSTPONED` | `POSTPONED` event that still has counted tickets | `ticketsIssued` |

  Items always include `eventId`, `eventTitle` and `startTime`. Clients should ignore unknown
  `type` values so new rules can be added later.
- `attending.ticketsUpcoming`: caller's `VALID` tickets for upcoming events.
- `attending.nextTicket`: the `VALID` ticket whose event starts earliest among upcoming events,
  or `null`.

A caller with no activity gets zeroed counters, `nextEvent: null`, `nextTicket: null` and
`attention: []`, never an error.

Errors: `401`.

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
  "location": "Antananarivo, Alliance Française",
  "priceUnit": "USD"
}
```
`description`, `location` and `priceUnit` optional; `isPublic` defaults to `true`, `priceUnit`
defaults to `USD`. `priceUnit` is a free-form currency/unit code, at most 10 characters.

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
  "priceUnit": "USD",
  "status": "OK",
  "myRole": "CREATOR",
  "createdAt": "2026-10-05T10:00:00Z",
  "updatedAt": "2026-10-05T10:00:00Z"
}
```
Errors: `400` (e.g. `endTime` must be after `startTime`).

### GET `/events`
Public. Lists events where `isPublic = true`.

Query params: `q` (title search), `from`, `to` (filter on `startTime`), `status` (default `OK`), `isMine`, `isPublic`, `page`, `size`, `sort` (default `startTime,asc`).

- `isMine=true` (requires authentication) lists only events the caller organizes (`CREATOR` or `ADMIN`) and lifts the `isPublic = true` restriction, so private events are included by default.
- `isPublic` is only applied when `isMine` is effective: `isMine=true&isPublic=false` returns the caller's private events, `isMine=true&isPublic=true` their public ones. Without an effective `isMine`, `isPublic` is ignored.
- Anonymous callers (or `isMine` absent/false) get the default public listing.

Response `200`: paginated wrapper with items:
```json
{
  "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
  "title": "Tana Jazz Night",
  "startTime": "2026-11-20T18:00:00Z",
  "endTime": "2026-11-20T22:00:00Z",
  "location": "Antananarivo, Alliance Française",
  "priceUnit": "USD",
  "status": "OK",
  "minPrice": 0.00
}
```

### GET `/events/{eventId}`
Public events are visible to everyone. A private event is visible only to its organizers (`CREATOR` / `ADMIN`): an anonymous caller gets `401`, and an authenticated non-organizer gets `403`. Unknown id: `404`.

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
  "priceUnit": "USD",
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
Creator only (`CREATOR`). Partial update. Only the fields present in the body are applied.

Request:
```json
{
  "title": "Tana Jazz Night 2",
  "description": "Updated description",
  "startTime": "2026-11-21T18:00:00Z",
  "endTime": "2026-11-21T22:00:00Z",
  "isPublic": false,
  "location": "New venue",
  "priceUnit": "EUR",
  "status": "POSTPONED"
}
```
Response `200`: full event object. Errors: `400`, `403`, `404`. Changing `priceUnit` relabels
the event's amounts without converting them.

### POST `/events/{eventId}/cancel` 🔒
Creator only (`CREATOR`). Sets `status = CANCELLED`. No body. Tickets are left as-is (check-in rejects tickets of a cancelled event).
Response `200`: event object.

### DELETE `/events/{eventId}` 🔒
`CREATOR` only. Allowed only if no tickets exist; otherwise `409` (`EVENT_HAS_TICKETS`, use cancel instead).
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
    "priceUnit": "USD",
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
    "location": "Antananarivo, Alliance Française",
    "priceUnit": "USD"
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

### GET `/tiers/{tierId}/check` 🔒
Reports whether the authenticated caller already holds a ticket in this tier. Counts the
capacity-consuming statuses (`VALID`, `USED`), so cancelled and refunded tickets do not count.

Response `200`:
```json
{ "isPresent": true }
```
Errors: `404` (unknown tier).

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
  "priceUnit": "USD",
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

## 7. Invitations

`invitationStatus`: `PENDING` | `ACCEPTED` | `DECLINED`

Invitations are how a `CREATOR` recruits `ADMIN`s for an event. The creator invites a user; the invited user accepts (becoming an `ADMIN` in `event_organizer`) or refuses. Only a user who does not already organize the event can be invited, and the creator cannot be invited. Inviting does not by itself grant any access: the receiver becomes an organizer only once they accept.

### POST `/events/{eventId}/invitations` 🔒
`CREATOR` only. Invites a user to become an `ADMIN`. Provide either `username` or `email`.

Request:
```json
{
  "username": "johnsmith"
}
```
(Alternative: `"email"`.)

Response `201`:
```json
{
  "id": "d4f9e2a1-0000-4000-8000-000000000010",
  "event": {
    "id": "5e0c2a38-3f1b-4f6a-9b0e-7c1d2e3f4a5b",
    "title": "Tana Jazz Night",
    "startTime": "2026-11-20T18:00:00Z",
    "location": "Antananarivo, Alliance Française",
    "priceUnit": "USD"
  },
  "sender": { "id": "0b6f1c5e-9d0a-4b7e-8f3a-2a1c6e9b7d10", "fullName": "Jane Doe", "username": "janedoe" },
  "receiver": { "id": "9a8b7c6d-0000-4000-8000-000000000009", "fullName": "John Smith", "username": "johnsmith" },
  "invitationStatus": "PENDING",
  "createdAt": "2026-10-05T10:00:00Z"
}
```
Errors: `400` (neither or both of `username`/`email`), `403` (`NOT_THE_CREATOR`), `404` (event or user not found), `409` (`ALREADY_ORGANIZER` the target already organizes this event, `ALREADY_INVITED` a `PENDING` invitation already exists for the target, `CANNOT_INVITE_CREATOR` the target is the creator/self).

### GET `/invitations` 🔒
The caller's invitations.

Query params: `direction` (`received` default, `sent`, `all`), `status`, `page`, `size`, `sort` (default `createdAt,desc`).
Response `200`: paginated wrapper of the invitation object above.
Errors: `400`, `401`.

### GET `/invitations/{invitationId}` 🔒
Allowed for the sender (the event's creator) and the receiver.
Response `200`: invitation object.
Errors: `403` (neither sender nor receiver), `404`.

### PATCH `/invitations/{invitationId}` 🔒
Receiver only. Accept or refuse a `PENDING` invitation.

Request:
```json
{
  "invitationStatus": "ACCEPTED"
}
```
`ACCEPTED` adds the receiver to `event_organizer` with role `ADMIN`, which also grants access to a private event. `DECLINED` only updates the invitation. `PENDING` is rejected.

Response `200`: updated invitation object with the new `invitationStatus`.
Errors: `400` (`PENDING` or unknown value), `403` (not the receiver), `404`, `409` (`INVITATION_NOT_PENDING`).

### DELETE `/invitations/{invitationId}` 🔒
Sender (the event's creator) only. Removes the invitation.
Response `204`. Errors: `403` (not the sender), `404`.

---

## 8. Endpoint summary

| Method | Path | Auth | Who |
|---|---|---|---|
| POST | `/auth/register` | none | anyone |
| POST | `/auth/login` | none | anyone |
| POST | `/auth/refresh` | refresh cookie | anyone |
| POST | `/auth/logout` | 🔒 | user |
| GET | `/users` | 🔒 | user |
| GET / PATCH / DELETE | `/users/me` | 🔒 | user |
| GET | `/users/me/dashboard` | 🔒 | user |
| POST | `/users/me/password` | 🔒 | user |
| POST | `/events` | 🔒 | user |
| GET | `/events` | optional | public; `isMine=true` for the caller's organized events |
| GET | `/events/{eventId}` | optional | public; private → organizer only (401 anon / 403 non-organizer) |
| PATCH | `/events/{eventId}` | 🔒 | creator |
| POST | `/events/{eventId}/cancel` | 🔒 | creator |
| DELETE | `/events/{eventId}` | 🔒 | creator |
| GET / POST | `/events/{eventId}/organizers` | 🔒 | organizer / creator |
| DELETE | `/events/{eventId}/organizers/{organizerId}` | 🔒 | creator |
| POST | `/events/{eventId}/invitations` | 🔒 | creator |
| GET | `/invitations` | 🔒 | user (own) |
| GET | `/invitations/{invitationId}` | 🔒 | sender or receiver |
| PATCH | `/invitations/{invitationId}` | 🔒 | receiver |
| DELETE | `/invitations/{invitationId}` | 🔒 | sender |
| GET | `/events/{eventId}/tiers` | optional | same as event |
| POST | `/events/{eventId}/tiers` | 🔒 | organizer |
| PATCH / DELETE | `/tiers/{tierId}` | 🔒 | organizer |
| POST | `/tiers/{tierId}/tickets` | 🔒 | user |
| GET | `/tiers/{tierId}/check` | 🔒 | user |
| GET | `/tickets/me` | 🔒 | user |
| GET | `/tickets/{ticketId}` | 🔒 | owner / organizer |
| POST | `/tickets/{ticketId}/check-in` | 🔒 | organizer |
| POST | `/tickets/{ticketId}/cancel` | 🔒 | owner |
| POST | `/tickets/{ticketId}/refund` | 🔒 | organizer |
| GET | `/events/{eventId}/tickets` | 🔒 | organizer |
| GET | `/events/{eventId}/stats` | 🔒 | organizer |

---

## Notes and open decisions

- **Currency**: each event carries a `priceUnit` (free-form, default `USD`) that labels all of
  its amounts. It is not a currency conversion: changing it does not restate existing `price`
  or `price_paid` values. The dashboard's aggregate `revenue` is unit-less because it sums
  events that may use different units.
- **Payments**: the schema has `price_paid` but no payment table. For paid tiers, `POST /tiers/{tierId}/tickets` currently issues the ticket directly. If you add real payments, this endpoint should become a checkout flow (create a pending order, then confirm via webhook).
- **Event cancellation**: `cancel` leaves tickets `VALID` (they can be refunded individually); check-in rejects tickets of a `CANCELLED` event.
- **`validUntil`**: nullable in the schema. A sensible default is the event's `endTime` at issue time.
- **Postponed events**: if `startTime`/`endTime` change, existing tickets' `validUntil` should be updated too.
- **Private events**: access is by direct link (UUID) unless the caller is an organizer. `POST /events/{eventId}/invitations` lets the `CREATOR` recruit `ADMIN`s; accepting an invitation grants organizer access to the private event. A pending invitee is not yet an organizer, so `GET /events/{eventId}` still returns `403` until they accept.