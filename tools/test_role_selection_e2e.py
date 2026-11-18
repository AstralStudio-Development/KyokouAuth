"""Synthetic verifier negatives only. No JVM, account, keys, listener or native-pass claims."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("role_selection_e2e", Path(__file__).with_name("role-selection-e2e.py"))
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)


class RoleSelectionEvidenceTests(unittest.TestCase):
    HELD = "11111111-1111-4111-9111-111111111111"
    FINAL = "22222222-2222-4222-9222-222222222222"
    REPLAY = "33333333-3333-4333-9333-333333333333"
    WIRE = "44444444-4444-4444-9444-444444444444"
    WIRE_FINAL = "55555555-5555-4555-9555-555555555555"

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="role-evidence-unit-")
        self.addCleanup(self.temp.cleanup)
        self.run = Path(self.temp.name)
        self.fixture = {"platform": "velocity", "mode": "floodgate", "role": "microsoft", "scenario": "linked",
                        "roleU": e2e.U, "floodgateMessages": True}
        self.result = {"platform": "velocity", "mode": "floodgate", "role": "microsoft", "scenario": "linked",
                       "ordinaryReconnects": 1, "transferPackets": 0, "cookiePackets": 0,
                       "menuSlots": 27, "wireSlots": 63, "icons": ["NAME_TAG", "DIAMOND"], "iconSlots": [12, 14],
                       "chooserFormAcknowledged": True, "finalFormAcknowledged": True, "finalPlay": True,
                       "selectionDisconnect": True, "hintReplayRequiresChooser": True,
                       "sockets": [self.socket("initial", self.WIRE, 41001, "a"),
                                   self.socket("final", self.WIRE_FINAL, 41002, "b")],
                       "replaySocket": self.socket("replay", "66666666-6666-4666-9666-666666666666", 41003, "c")}
        self.console = "\n".join((
            f"ROLE_E2E_NATIVE_SOCKET session={self.HELD} remotePort=41001 state=held",
            f"ROLE_E2E_NATIVE_HELD session={self.HELD}",
            f"ROLE_E2E_FG_PROOF session={self.HELD} phase=1 linked=true",
            f"ROLE_E2E_MENU session={self.HELD} slots=27 u=NAME_TAG j=DIAMOND",
            f"ROLE_E2E_FG_MESSAGE_BARRIER session={self.HELD} phase=chooser source=PLAYER held=true bytes=3",
            "Couldn't find stored form with id -12345 for player JRole",
            f"ROLE_E2E_NATIVE_SOCKET session={self.FINAL} remotePort=41002 state=final",
            f"ROLE_E2E_NATIVE_FINAL session={self.FINAL} role=microsoft uuid={e2e.U} name=URole",
            f"ROLE_E2E_FG_PROOF session={self.FINAL} phase=1 linked=true",
            f"ROLE_E2E_AUTHORIZED_BACKEND session={self.FINAL}",
            f"ROLE_E2E_FG_MESSAGE_BARRIER session={self.FINAL} phase=final source=PLAYER held=false bytes=3",
            "Couldn't find stored form with id -12345 for player JRole",
            f"ROLE_E2E_NATIVE_SOCKET session={self.REPLAY} remotePort=41003 state=held",
            f"ROLE_E2E_NATIVE_HELD session={self.REPLAY}",
            f"ROLE_E2E_FG_PROOF session={self.REPLAY} phase=1 linked=true",
            f"ROLE_E2E_MENU session={self.REPLAY} slots=27 u=NAME_TAG j=DIAMOND"))
        self.events = [{"event": "handshake"}, {"event": "login", "uuid": e2e.U, "name": "URole"},
                       {"event": "authorized", "uuid": e2e.U, "name": "URole", "canary": "independent-u-data",
                        "hmac": True, "nonce": self.FINAL},
                       {"event": "play", "uuid": e2e.U, "name": "URole"},
                       {"event": "play-message-complete", "uuid": e2e.U, "name": "URole"}]
        self.output = ""

    @staticmethod
    def socket(phase, wire, port, fingerprint):
        return {"phase": phase, "wireSession": wire, "localPort": port, "freshOfficialCipher": True,
                "proofFingerprint": fingerprint * 64, "intent": "LOGIN"}

    def verify(self):
        (self.run / "isolated-role-fixture.json").write_text(json.dumps(self.fixture), encoding="utf-8")
        (self.run / "console.log").write_text(self.console, encoding="utf-8")
        (self.run / "backend-events.jsonl").write_text("\n".join(map(json.dumps, self.events)), encoding="utf-8")
        (self.run / "role-selector.bin").write_bytes(b"synthetic-not-a-native-result")
        output = "ROLE_CLIENT_RESULT " + json.dumps(self.result) + self.output
        for value in self.result["sockets"] + ([self.result["replaySocket"]] if "replaySocket" in self.result else []):
            output += "\nROLE_CLIENT_SOCKET " + json.dumps(value, separators=(",", ":"))
        for phase in ("chooser", "final"):
            output += f"\nROLE_CLIENT_FG_MESSAGE_SENT phase={phase} channel=floodgate:form formId=-12345 payload=valid-json-0"
        return e2e.verify_evidence(self.run, "floodgate", self.fixture["role"], output)

    def same_identity(self):
        self.fixture["role"] = self.result["role"] = "premium"
        self.result.update(ordinaryReconnects=0, selectionDisconnect=False)
        self.result["sockets"] = self.result["sockets"][:1]
        self.console = self.console.replace(f"ROLE_E2E_NATIVE_SOCKET session={self.FINAL} remotePort=41002 state=final\n", "")
        self.console = self.console.replace(f"ROLE_E2E_FG_PROOF session={self.FINAL} phase=1 linked=true\n", "")
        self.console = self.console.replace(self.FINAL, self.HELD).replace(f"uuid={e2e.U} name=URole", f"uuid={e2e.J} name=JRole").replace("role=microsoft", "role=premium")
        for value in self.events:
            if "uuid" in value: value.update(uuid=e2e.J, name="JRole")
            if "canary" in value: value.update(canary="independent-j-data", nonce=self.HELD)

    def test_different_identity_requires_one_new_ordinary_login(self):
        self.assertEqual(1, self.verify()["nativeBackendHandshakes"])

    def test_same_identity_authorizes_on_the_original_socket(self):
        self.same_identity()
        self.assertEqual(0, self.verify()["ordinaryReconnects"])

    def test_native_transfer_packet_is_an_immediate_failure(self):
        self.result["transferPackets"] = 1
        with self.assertRaisesRegex(AssertionError, "Transfer|cookie"):
            self.verify()

    def test_cookie_storage_cannot_fake_an_ordinary_reconnect(self):
        self.result["cookiePackets"] = 1
        with self.assertRaisesRegex(AssertionError, "Transfer|cookie"):
            self.verify()

    def test_transfer_intent_is_not_a_fresh_login(self):
        self.result["sockets"][1]["intent"] = "TRANSFER"
        with self.assertRaisesRegex(AssertionError, "ordinary LOGIN"):
            self.verify()

    def test_reused_cipher_is_not_fresh_floodgate_proof(self):
        self.result["sockets"][1]["proofFingerprint"] = "a" * 64
        with self.assertRaisesRegex(AssertionError, "fresh official cipher"):
            self.verify()

    def test_different_identity_cannot_reuse_socket_or_proxy_session(self):
        for field in ("wireSession", "localPort"):
            original = self.result["sockets"][1][field]
            self.result["sockets"][1][field] = self.result["sockets"][0][field]
            with self.assertRaisesRegex(AssertionError, "distinct"):
                self.verify()
            self.result["sockets"][1][field] = original

    def test_tcp_mapping_must_match_exact_native_session(self):
        self.console = self.console.replace("remotePort=41002", "remotePort=41009")
        with self.assertRaisesRegex(AssertionError, "unique native TCP"):
            self.verify()

    def test_old_chooser_cannot_reach_backend(self):
        self.events[2]["nonce"] = self.HELD
        with self.assertRaisesRegex(AssertionError, "exact final native session"):
            self.verify()

    def test_same_identity_cannot_sneak_in_a_second_socket(self):
        self.same_identity()
        self.result["sockets"].append(self.socket("final", self.WIRE_FINAL, 41002, "b"))
        with self.assertRaisesRegex(AssertionError, "connection count"):
            self.verify()

    def test_handler_ack_without_official_form_log_is_not_a_pass(self):
        self.console = self.console.replace("Couldn't find stored form with id -12345 for player JRole", "")
        with self.assertRaisesRegex(AssertionError, "Official FormChannel"):
            self.verify()

    def test_official_form_log_without_barrier_is_not_a_pass(self):
        self.console = self.console.replace("ROLE_E2E_FG_MESSAGE_BARRIER", "missing")
        with self.assertRaisesRegex(AssertionError, "exact official handler barrier"):
            self.verify()

    def test_parse_error_or_official_source_kick_is_not_a_pass(self):
        for error in ("Only Floodgate players can send floodgate messages!", "Invalid form response", "Error while processing form response!", "ROLE_E2E_ASSERTION_FAILED"):
            with self.subTest(error=error):
                original = self.console
                self.console += "\n" + error
                with self.assertRaises(AssertionError): self.verify()
                self.console = original

    def test_consumed_hint_replay_must_reopen_held_chooser(self):
        self.console = self.console.replace(f"ROLE_E2E_MENU session={self.REPLAY}", "missing")
        with self.assertRaisesRegex(AssertionError, "replay.*chooser"):
            self.verify()

    def test_second_backend_handshake_is_never_a_pass(self):
        self.events.append({"event": "handshake"})
        with self.assertRaisesRegex(AssertionError, "one backend handshake"):
            self.verify()

    def test_wrong_canary_or_uuid_is_not_a_pass(self):
        self.events[2]["canary"] = "independent-j-data"
        with self.assertRaisesRegex(AssertionError, "UUID-keyed canary"):
            self.verify()

    def test_java_advanced_role_is_explicitly_out_of_scope(self):
        self.fixture["mode"] = self.result["mode"] = "java"
        with self.assertRaisesRegex(AssertionError, "Java basic"):
            self.verify()

    def bungee(self):
        self.fixture["platform"] = self.result["platform"] = "bungee"
        self.events[2].pop("hmac")
        self.events[2].pop("nonce")
        self.events[2].update(legacyForwarded=True, privateTicketAbsent=True, connectionNonceAbsent=True, testSession=self.FINAL)
        for session in (self.HELD, self.REPLAY):
            self.console += f"\nROLE_E2E_PRIVATE_LIMBO_AUTHORITY session={session} consumed=true target=private-engine"

    def test_bungee_separates_private_limbo_authority_from_real_backend(self):
        self.bungee()
        self.assertEqual(1, self.verify()["nativeBackendHandshakes"])

    def test_bungee_cannot_borrow_velocity_hmac_evidence(self):
        self.fixture["platform"] = self.result["platform"] = "bungee"
        with self.assertRaisesRegex(AssertionError, "Bungee.*authority|Bungee.*legacy"):
            self.verify()

    def test_bungee_rejects_private_authority_leak_to_real_backend(self):
        self.bungee()
        self.events[2]["privateTicketAbsent"] = False
        with self.assertRaisesRegex(AssertionError, "private.*real backend"):
            self.verify()

    def test_bungee_requires_actual_private_engine_admission(self):
        self.bungee()
        self.console = self.console.replace("ROLE_E2E_PRIVATE_LIMBO_AUTHORITY", "missing")
        with self.assertRaisesRegex(AssertionError, "private Limbo authority"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
