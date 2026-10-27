# Migration and rollback

The 2.0 alpha does not rename or delete 1.x circuit, fragment, stone, mana or audit tables. ArcSys tables are additive and created in a transaction. Existing HMAC material is reused. Existing 72 fragment IDs in the current source stay valid; the historical 69-ID audit is retained as a regression fixture.

Before a production upgrade:

1. Stop writes and copy the SQLite database, WAL and SHM files together.
2. Preserve the plugin configuration and HMAC secret.
3. Run schema dry-run and compatibility tests against a copied database.
4. Start 2.0 in a staging server and verify fragment issuance, stored fragments, active circuits, stones and `/msf fragment give`.
5. Deploy only a release candidate whose migration and rollback tests pass.

Rollback to 1.x requires restoring the pre-upgrade database copy. ArcSys source tables are not read by 1.x, but a database partially written by a newer schema must not be presented as a tested downgrade.

The current production 1.7.0 installation remains untouched while 2.0 is alpha.
