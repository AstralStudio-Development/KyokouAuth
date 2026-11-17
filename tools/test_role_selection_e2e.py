"""Evidence-validator regressions only: synthetic logs, no JVM, accounts, keys or sockets."""
import importlib.util
import base64
import hashlib
import struct
import zlib
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
    VISUAL = "33333333-3333-4333-9333-333333333333"
    OFFICIAL = "Couldn't find stored form with id -12345 for player JRole"
    BARRIER = "ROLE_E2E_FG_MESSAGE_BARRIER"
    KICK = "Only Floodgate players can send floodgate messages!"

    def setUp(self):
        # Kept within the verifier's existing isolated-fixture root boundary.
        self.temporary = tempfile.TemporaryDirectory(prefix="role-evidence-unit-", dir=e2e.ROOT / ".narrafork")
        self.addCleanup(self.temporary.cleanup)
        self.run = Path(self.temporary.name)
        self.result = {
            "role": "microsoft", "mode": "floodgate", "newSocket": True, "playReady": True,
            "heads": [12, 14], "menuSlots": 63, "hintReplayRequiresChooser": True,
            "floodgateMessages": True, "chooserFormAcknowledged": True, "finalFormAcknowledged": True, "finalPlay": True,
        }
        self.console = "\n".join((
            f"ROLE_E2E_NATIVE_HELD session={self.HELD}", "ROLE_E2E_LIMBO_READY", "ROLE_E2E_MENU",
            self.OFFICIAL, f"{self.BARRIER} session={self.HELD} phase=chooser source=PLAYER held=true bytes=3",
            f"ROLE_E2E_NATIVE_FINAL session={self.FINAL}", "ROLE_E2E_AUTHORIZED_BACKEND",
            self.OFFICIAL, f"{self.BARRIER} session={self.FINAL} phase=final source=PLAYER held=false bytes=3",
        ))
        self.events = [
            {"event": "handshake"}, {"event": "login", "uuid": e2e.U, "name": "URole"},
            {"event": "authorized", "uuid": e2e.U, "name": "URole", "canary": "independent-u-data",
             "hmac": True, "nonce": self.FINAL},
            {"event": "play", "uuid": e2e.U, "name": "URole"},
            {"event": "play-message-complete", "uuid": e2e.U, "name": "URole"},
        ]
        self.fixture = {"floodgateMessages": True}
        self.extra_output = ""

    def verify(self, suffix=""):
        (self.run / "isolated-role-fixture.json").write_text(json.dumps(self.fixture), encoding="utf-8")
        (self.run / "console.log").write_text(self.console, encoding="utf-8")
        (self.run / "backend-events.jsonl").write_text("\n".join(json.dumps(event) for event in self.events), encoding="utf-8")
        output = "ROLE_CLIENT_RESULT " + json.dumps(self.result) + "\n" + suffix + self.extra_output
        for phase in ("chooser", "final"):
            output += f"\nROLE_CLIENT_FG_MESSAGE_SENT phase={phase} channel=floodgate:form formId=-12345 payload=valid-json-0"
        return e2e.verify_evidence(self.run, self.result["mode"], "microsoft", output)

    def test_complete_synthetic_evidence_is_accepted(self):
        self.assertEqual(1, self.verify()["nativeBackendHandshakes"])

    def test_default_fixture_does_not_require_opt_in_message_probes(self):
        self.fixture = {}
        self.result.pop("floodgateMessages")
        self.result.pop("chooserFormAcknowledged")
        self.console = self.console.replace(self.OFFICIAL, "").replace(self.BARRIER, "")
        self.assertEqual(1, self.verify()["nativeBackendHandshakes"])

    def test_ack_without_official_handler_evidence_is_not_a_pass(self):
        self.console = self.console.replace(self.OFFICIAL, "")
        with self.assertRaisesRegex(AssertionError, "Official FormChannel did not process"):
            self.verify()

    def test_official_handler_without_post_handler_barrier_is_not_a_pass(self):
        self.console = self.console.replace(self.BARRIER, "")
        with self.assertRaisesRegex(AssertionError, "No unique native LAST message barrier"):
            self.verify()

    def test_missing_client_ack_is_not_a_pass(self):
        self.result["chooserFormAcknowledged"] = False
        with self.assertRaisesRegex(AssertionError, "handler wire barrier"):
            self.verify()

    def test_exact_kick_in_either_log_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, self.KICK):
            self.verify(self.KICK)
        self.console += "\n" + self.KICK
        with self.assertRaisesRegex(AssertionError, self.KICK):
            self.verify()

    def test_parse_errors_cannot_count_as_handled_success(self):
        for error in ("Invalid form response", "Error while processing form response!", "Exception while handling plugin message"):
            with self.subTest(error=error):
                console = self.console
                self.console += "\n" + error
                with self.assertRaisesRegex(AssertionError, "parse/handler error is not a pass"):
                    self.verify()
                self.console = console

    def test_final_play_cannot_be_replaced_by_just_login_success(self):
        self.result["finalPlay"] = False
        with self.assertRaisesRegex(AssertionError, "handler wire barrier"):
            self.verify()
        self.result["finalPlay"] = True
        self.events = [event for event in self.events if event["event"] != "play-message-complete"]
        with self.assertRaisesRegex(AssertionError, "actual backend PLAY"):
            self.verify()

    def test_final_message_requires_the_final_native_session(self):
        self.console = self.console.replace(f"{self.BARRIER} session={self.FINAL}", f"{self.BARRIER} session={self.HELD}")
        with self.assertRaisesRegex(AssertionError, "wrong exact native session"):
            self.verify()

    def java_negative(self):
        self.result = {
            "mode": "java", "role": "microsoft", "floodgateMessages": True, "forgedFloodgateRejected": True,
            "chooserFormSent": True, "officialKick": self.KICK, "newSocket": False, "playReady": True,
        }
        self.console = "\n".join((f"ROLE_E2E_NATIVE_HELD session={self.HELD}", "ROLE_E2E_LIMBO_READY",
                                   "ROLE_E2E_MENU", "ROLE_E2E_FG_JAVA_REJECT_BARRIER", self.KICK))
        self.events = []

    def test_java_same_valid_response_is_only_accepted_as_exact_source_rejection(self):
        self.java_negative()
        self.assertEqual(0, self.verify()["nativeBackendHandshakes"])
        self.console = self.console.replace(self.KICK, "Invalid form response")
        with self.assertRaisesRegex(AssertionError, "fail closed"):
            self.verify()

    def test_java_exact_kick_cannot_hide_backend_admission(self):
        self.java_negative()
        self.events = [{"event": "handshake"}]
        with self.assertRaisesRegex(AssertionError, "reached the backend"):
            self.verify()

    def test_java_negative_cannot_receive_success_barrier(self):
        self.java_negative()
        self.console += "\n" + self.BARRIER
        with self.assertRaisesRegex(AssertionError, "not a pure official source rejection"):
            self.verify()

    @staticmethod
    def sprite(red, blue):
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
        pixels = b"".join(b"\0" + bytes((red, 40, blue, 0 if y == 0 else 255)) * 32 for y in range(32))
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")

    def avatar(self, scenario="cold"):
        visual = self.VISUAL
        wire_sessions = {"chooser": "44444444-4444-4444-9444-444444444444", "final": "55555555-5555-4555-9555-555555555555", "visual": "66666666-6666-4666-9666-666666666666"}
        ports = {"chooser": 42001, "final": 42002, "visual": 42000}
        xuid = e2e.XUID + 31 if scenario == "new-user" else e2e.XUID
        role_u = str(e2e.uuid.UUID(int=xuid))
        self.fixture.update(avatars=True, avatarCase=scenario, xuid=xuid, roleU=role_u)
        for event in self.events:
            if "uuid" in event:
                event["uuid"] = role_u
        pngs = (self.sprite(220, 40), self.sprite(40, 220))
        fingerprint = hashlib.sha256(b"kyokouauth-role-avatar-v1\0" + b"".join(struct.pack(">I", len(png)) + png for png in pngs)).hexdigest()
        snapshot = {"schema": 1, "state": "ready", "fingerprint": fingerprint,
                    "microsoft_png": base64.b64encode(pngs[0]).decode(), "premium_png": base64.b64encode(pngs[1]).decode(),
                    "microsoft_default": True, "premium_default": False}
        (self.run / "avatar-snapshot-observed.json").write_text(json.dumps(snapshot), encoding="utf-8")
        fallback = scenario == "fallback"
        self.result["avatars"] = {"enabled": True, "scenario": scenario, "xuid": xuid, "fingerprint": fingerprint,
            "snapshotHttpVerified": True, "distinctSprites": True, "visualTransfers": 0 if scenario == "hit" else 1,
            "visualWireSession": "" if scenario == "hit" else wire_sessions["visual"],
            "chooserWireSession": wire_sessions["chooser"], "finalWireSession": wire_sessions["final"],
            "visualPort": 0 if scenario == "hit" else ports["visual"], "chooserPort": ports["chooser"], "finalPort": ports["final"],
            "freshProof": True, "visualRoleHandoffUsed": False, "roleCookieMinted": False,
            "offeredMatch": not fallback, "fallback": fallback, "unavailableNotice": fallback,
            "itemCmd": [0x4B5901, 0x4B5902], "nonItalic": True,
            "geyserStatusProof": "controlled-loopback-fixture-not-geyser", "rendered": False, "applied": False}
        phases = {"chooser": self.HELD, "final": self.FINAL}
        if scenario != "hit":
            phases["visual"] = visual
            self.console += f"\nROLE_E2E_NATIVE_HELD session={visual}"
        for phase, session in phases.items():
            self.extra_output += f"\nROLE_CLIENT_AVATAR_SOCKET phase={phase} wireSession={wire_sessions[phase]} localPort={ports[phase]} freshOfficialCipher=true"
            self.console += f"\nROLE_E2E_AVATAR_NATIVE_SOCKET session={session} remotePort={ports[phase]} state={'final' if phase == 'final' else 'held'}"
            self.console += f"\nROLE_E2E_FG_PROOF session={session} phase=4 linked=true"
        self.console += f"\nROLE_E2E_AVATAR_MENU session={self.HELD} uCmd=4937985 jCmd=4937986 profile=unchanged"
        for call in range(1, 2 if scenario == "hit" else 3):
            self.console += f"\nROLE_E2E_AVATAR_PREPARED call={call} xuid={xuid} fingerprint={fingerprint} source=real-service syntheticSkins=true"
        statuses = [{"xuid": xuid, "status": 200, "fingerprint": fingerprint, "fallback": fallback,
                     "proof": "controlled-loopback-fixture-not-geyser"}]
        if scenario != "hit":
            statuses.insert(0, {"xuid": xuid, "status": 404, "proof": "controlled-loopback-fixture-not-geyser"})
        (self.run / "avatar-status-events.jsonl").write_text("\n".join(json.dumps(status) for status in statuses), encoding="utf-8")
        if scenario in ("hit", "changed", "new-user"):
            baseline = {"xuid": e2e.XUID, "fingerprint": fingerprint if scenario == "hit" else "0" * 64}
            (self.run / "avatar-baseline.json").write_text(json.dumps(baseline), encoding="utf-8")
        return self.result["avatars"]

    def test_avatar_cold_hit_changed_new_user_and_fallback_evidence(self):
        for scenario in e2e.AVATAR_CASES:
            with self.subTest(scenario=scenario):
                self.setUp()
                self.avatar(scenario)
                self.assertEqual(1, self.verify()["nativeBackendHandshakes"])

    def test_avatar_requires_fresh_proof_on_visual_socket(self):
        evidence = self.avatar()
        self.console = self.console.replace(f"ROLE_E2E_FG_PROOF session={self.VISUAL} phase=4 linked=true", "")
        with self.assertRaisesRegex(AssertionError, "fresh official Floodgate proof"):
            self.verify()

    def test_avatar_wire_and_proxy_session_ids_are_distinct_domains(self):
        evidence = self.avatar()
        self.assertNotEqual(self.HELD, evidence["chooserWireSession"])
        self.assertEqual(1, self.verify()["nativeBackendHandshakes"])

    def test_avatar_tcp_mapping_must_be_unique_and_exact(self):
        self.avatar()
        self.console = self.console.replace("remotePort=42000 state=held", "remotePort=42009 state=held")
        with self.assertRaisesRegex(AssertionError, "unique matching native proxy connection"):
            self.verify()
        self.console = self.console.replace("remotePort=42009 state=held", "remotePort=42000 state=held")
        self.console += f"\nROLE_E2E_AVATAR_NATIVE_SOCKET session={self.HELD} remotePort=42000 state=held"
        with self.assertRaisesRegex(AssertionError, "unique matching native proxy connection"):
            self.verify()

    def test_avatar_socket_cannot_borrow_another_proxy_proof(self):
        evidence = self.avatar()
        self.console = self.console.replace(f"ROLE_E2E_FG_PROOF session={self.VISUAL} phase=4 linked=true", "")
        self.console += f"\nROLE_E2E_FG_PROOF session={evidence['visualWireSession']} phase=4 linked=true"
        with self.assertRaisesRegex(AssertionError, "fresh official Floodgate proof"):
            self.verify()

    def test_avatar_rejects_more_than_one_reload(self):
        self.avatar()["visualTransfers"] = 2
        with self.assertRaisesRegex(AssertionError, "zero/one native Transfer"):
            self.verify()

    def test_avatar_visual_retry_cannot_use_role_handoff_or_cookie(self):
        evidence = self.avatar()
        evidence["visualRoleHandoffUsed"] = True
        with self.assertRaisesRegex(AssertionError, "RoleHandoff or role cookie"):
            self.verify()
        evidence["visualRoleHandoffUsed"] = False
        evidence["roleCookieMinted"] = True
        with self.assertRaisesRegex(AssertionError, "RoleHandoff or role cookie"):
            self.verify()

    def test_avatar_fallback_never_counts_as_render_ack(self):
        evidence = self.avatar("fallback")
        evidence["rendered"] = True
        with self.assertRaisesRegex(AssertionError, "not an applied/render ACK"):
            self.verify()
        evidence["rendered"] = False
        evidence["offeredMatch"] = True
        with self.assertRaisesRegex(AssertionError, "Fallback must remain explicitly unavailable"):
            self.verify()

    def test_avatar_fingerprint_must_hash_actual_http_pngs(self):
        self.avatar()["fingerprint"] = "f" * 64
        with self.assertRaisesRegex(AssertionError, "independently hashed HTTP PNG"):
            self.verify()

    def test_avatar_menu_cannot_precede_visual_reload(self):
        evidence = self.avatar()
        self.console += f"\nROLE_E2E_AVATAR_MENU session={self.VISUAL} uCmd=4937985 jCmd=4937986 profile=unchanged"
        with self.assertRaisesRegex(AssertionError, "menu appeared before"):
            self.verify()

    def test_avatar_changed_fingerprint_cannot_reuse_baseline(self):
        evidence = self.avatar("changed")
        (self.run / "avatar-baseline.json").write_text(json.dumps({"xuid": e2e.XUID, "fingerprint": evidence["fingerprint"]}), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "stale presentation fingerprint"):
            self.verify()

    def test_held_or_replay_backend_connection_is_rejected(self):
        self.events.insert(0, {"event": "handshake"})
        with self.assertRaisesRegex(AssertionError, "second backend handshake"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
