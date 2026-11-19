"""Synthetic evidence-validator tests; these do not count as native login or account proof."""
import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('profile_claim_e2e', Path(__file__).with_name('profile-claim-e2e.py'))
e2e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e2e)


class InteractiveClaimEvidenceTests(unittest.TestCase):
    SEED = '11111111-1111-4111-9111-111111111111'
    CLAIM = '22222222-2222-4222-9222-222222222222'
    PROVISIONAL = '12345678-1234-3234-9234-123456789abc'

    def fixture(self, scenario='claim'):
        lines = [
            f'CLAIM_NATIVE_BACKEND stage=seed session={self.SEED} uuid={e2e.OLD}',
            f'CLAIM_NATIVE_DISCONNECTED stage=seed session={self.SEED}',
            f'CLAIM_NATIVE_LOGIN stage=claim session={self.CLAIM} uuid={self.PROVISIONAL} authenticated=false',
            f'CLAIM_NATIVE_MENU session={self.CLAIM} index=1 generation=aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa',
            f'CLAIM_NATIVE_OLD_PROOF session={self.CLAIM} synthetic=true',
            f'CLAIM_NATIVE_MENU session={self.CLAIM} index=2 generation=bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb',
        ]
        if scenario in ('claim', 'disconnect'):
            lines += [
                f'CLAIM_NATIVE_MICROSOFT_BEGIN session={self.CLAIM} synthetic=true',
                f'CLAIM_NATIVE_MICROSOFT_PROVED session={self.CLAIM} synthetic=true',
                f'CLAIM_NATIVE_MENU session={self.CLAIM} index=3 generation=cccccccc-cccc-4ccc-9ccc-cccccccccccc',
            ]
        if scenario == 'claim':
            lines.append(f'CLAIM_NATIVE_COMMITTED session={self.CLAIM} uuid={e2e.OLD} sourceOffline=true')
        lines.append(f'CLAIM_NATIVE_DISCONNECTED stage=claim session={self.CLAIM}')
        if scenario != 'claim':
            progressed = scenario == 'disconnect'
            lines.append(f'CLAIM_NATIVE_NO_MUTATION case={scenario} menus={3 if progressed else 2} '
                         f'begins={1 if progressed else 0} exchanges={1 if progressed else 0} backend=false')
        return '\n'.join(lines)

    def test_full_confirmed_flow_and_three_noncommitting_scenarios(self):
        for scenario in ('claim', 'cancel', 'disconnect', 'expired'):
            with self.subTest(scenario=scenario):
                e2e.assert_interactive_markers(self.fixture(scenario), scenario)

    def test_held_claimant_backend_connection_is_not_a_pass(self):
        text = self.fixture() + f'\nCLAIM_NATIVE_BACKEND stage=claim session={self.CLAIM} uuid={e2e.OLD}'
        with self.assertRaises(AssertionError):
            e2e.assert_interactive_markers(text, 'claim')

    def test_duplicate_commit_is_not_a_pass(self):
        text = self.fixture() + f'\nCLAIM_NATIVE_COMMITTED session={self.CLAIM} uuid={e2e.OLD} sourceOffline=true'
        with self.assertRaises(AssertionError):
            e2e.assert_interactive_markers(text, 'claim')

    def test_wrong_session_proof_and_reused_generation_are_not_a_pass(self):
        for before, after in (
            (f'CLAIM_NATIVE_OLD_PROOF session={self.CLAIM}', f'CLAIM_NATIVE_OLD_PROOF session={self.SEED}'),
            ('bbbbbbbb-bbbb-4bbb-9bbb-bbbbbbbbbbbb', 'aaaaaaaa-aaaa-4aaa-9aaa-aaaaaaaaaaaa'),
            ('sourceOffline=true', 'sourceOffline=false'),
        ):
            with self.subTest(before=before), self.assertRaises(AssertionError):
                e2e.assert_interactive_markers(self.fixture().replace(before, after), 'claim')

    def test_proof_cannot_follow_final_confirmation(self):
        lines = self.fixture().splitlines()
        lines[7], lines[8] = lines[8], lines[7]
        with self.assertRaises(AssertionError):
            e2e.assert_interactive_markers('\n'.join(lines), 'claim')

    def restart_fixture(self):
        session = '33333333-3333-4333-9333-333333333333'
        return '\n'.join((
            f'CLAIM_NATIVE_RESTART_LOADED uuid={e2e.OLD} migrationFlag=false',
            f'CLAIM_NATIVE_LOGIN stage=reauth session={session} uuid={e2e.OLD} authenticated=false',
            f'CLAIM_NATIVE_MICROSOFT_BEGIN session={session} synthetic=true',
            f'CLAIM_NATIVE_MICROSOFT_PROVED session={session} synthetic=true',
            f'CLAIM_NATIVE_REAUTH_BOUND session={session} uuid={e2e.OLD}',
            f'CLAIM_NATIVE_REAUTH_GRANTED session={session} uuid={e2e.OLD}',
            f'CLAIM_NATIVE_BACKEND stage=mapped session={session} uuid={e2e.OLD}',
        ))

    def private_fixture(self):
        marker = f'CLAIM_NATIVE_PRIVATE_SOURCE session={self.CLAIM} attempts=2 commandEvents=0 completionEvents=0 audit=AUTHME_PASSWORD_CLAIM'
        return self.fixture().replace('CLAIM_NATIVE_DISCONNECTED stage=claim', marker + '\nCLAIM_NATIVE_DISCONNECTED stage=claim')

    def test_private_source_requires_actual_wrong_then_correct_native_submissions(self):
        client = 'CLAIM_CLIENT_PRIVATE_SENT attempt=1 unsigned=true\nCLAIM_CLIENT_PRIVATE_SENT attempt=2 unsigned=true'
        e2e.assert_private_claim_markers(self.private_fixture(), client)

    def test_private_event_leaks_missing_attempt_or_password_log_cannot_be_a_pass(self):
        client = 'CLAIM_CLIENT_PRIVATE_SENT attempt=1 unsigned=true\nCLAIM_CLIENT_PRIVATE_SENT attempt=2 unsigned=true'
        variants = (
            (self.private_fixture().replace('commandEvents=0', 'commandEvents=1'), client, ''),
            (self.private_fixture().replace('completionEvents=0', 'completionEvents=1'), client, ''),
            (self.private_fixture().replace('attempts=2', 'attempts=1'), client, ''),
            (self.private_fixture(), client.replace('attempt=1', 'attempt=3'), ''),
            (self.private_fixture(), client, 'unexpected PassWord1'),
            (self.private_fixture(), client, 'unexpected PRIVATE_BAD_E2E_PASSWORD'),
        )
        for native, output, extra in variants:
            with self.subTest(extra=extra), self.assertRaises(AssertionError):
                e2e.assert_private_claim_markers(native, output, [extra])

    def test_restarted_proxy_requires_fresh_proof_before_mapped_backend(self):
        e2e.assert_restarted_claim_markers(self.fixture(), self.restart_fixture())

    def test_restarted_proxy_cannot_trust_a_binding_or_reuse_the_claim_session(self):
        for text in (
            self.restart_fixture().replace('CLAIM_NATIVE_MICROSOFT_PROVED', 'missing'),
            self.restart_fixture().replace('authenticated=false', 'authenticated=true'),
            self.restart_fixture().replace('33333333-3333-4333-9333-333333333333', self.CLAIM),
            self.restart_fixture().replace('CLAIM_NATIVE_REAUTH_GRANTED', 'missing'),
        ):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                e2e.assert_restarted_claim_markers(self.fixture(), text)

    def test_cancel_cannot_commit_or_omit_rollback_assertion(self):
        for text in (self.fixture('cancel').replace('CLAIM_NATIVE_NO_MUTATION', 'missing'),
                     self.fixture('cancel') + f'\nCLAIM_NATIVE_COMMITTED session={self.CLAIM} uuid={e2e.OLD} sourceOffline=true'):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                e2e.assert_interactive_markers(text, 'cancel')


if __name__ == '__main__':
    unittest.main()
