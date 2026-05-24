# Feature-hasher parity fixtures

Both the Python implementation
(`training/src/bullpen_training/registry_client/feature_hasher.py`) and
the Java implementation
(`backend/src/main/java/net/thebullpen/baseball/registry/FeatureSchemaHasher.java`)
must produce identical SHA-256 digests for every fixture listed in
`fixtures.json`.

Layout:

- `fixtures.json` — one entry per fixture: `name`, `input_file`, `expected_hash`.
- `<name>.json` — the input JSON document to hash.

Tests on each side iterate the manifest, hash the input, and assert
equality with the expected hash.

If you add a fixture: compute the expected hash with the Python
reference implementation, then run `FeatureSchemaParityIT` to confirm
the Java side agrees.
