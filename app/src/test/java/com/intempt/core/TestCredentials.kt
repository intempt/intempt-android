package com.intempt.core

// INT-6972 / INT-6973: test fixtures carried real ingestion keys. This one has the "<id>.<secret>"
// shape the validator and token() accept and the 32.32 length of a real key, but no entropy, so
// secret scanners and ApiKeyLiteralGuardTest both pass it.
internal const val FAKE_API_KEY = "testkeyid00000000000000000000000.testsecret0000000000000000000000"
