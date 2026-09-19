<!--
     SPDX-FileCopyrightText: The LineageOS Project
     SPDX-License-Identifier: Apache-2.0
-->

# Design documentation

These documents describe how RCS is built into Messaging: what each part is for, the contracts
between the parts, and the reasons behind the choices a reviewer would otherwise have to reconstruct
from the code. They assume familiarity with Android. Code comments stay short and point here for the
rationale.

## Reading order

1. [rcs/architecture.md](rcs/architecture.md): the two routes to an RCS network, how one is chosen
   per subscription, the send and receive paths, send status, and the database additions.
2. [rcs/provider-contract.md](rcs/provider-contract.md): the AIDL interface to a provider app, the
   append-only ordering rule and the runtime layout check.
3. [rcs/groups.md](rcs/groups.md) and [rcs/carrier-transport.md](rcs/carrier-transport.md) as
   needed.

## Index

### RCS

| Document | Content |
|---|---|
| [rcs/architecture.md](rcs/architecture.md) | App and provider split, the transport seam, discovery and binding, route selection, send gating, inbound dispatch, send status, schema |
| [rcs/provider-contract.md](rcs/provider-contract.md) | `IRcsProvider` and `IRcsProviderCallback`, parcelables, ordinal ordering rule, layout digest and pairing, delivery confirmation |
| [rcs/groups.md](rcs/groups.md) | Group conversations: mapping, creation, sending, inbound routing, group events, management, local mirrors, receipts |
| [rcs/carrier-transport.md](rcs/carrier-transport.md) | The in-app carrier SIP/MSRP transport: processes, SR and DR registration, Digest authentication, pager and session messaging, send status |

### Testing

| Document | Content |
|---|---|
| [testing.md](testing.md) | Host test suites and the source-scan guards: what each family pins |
