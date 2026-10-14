import importlib.util
from pathlib import Path
import socket
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('managed', Path(__file__).with_name('managed-local.py'))
managed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(managed)


class ManagedLocalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'private'

    def test_private_credentials_survive_repeated_prepare(self):
        managed.prepare(self.directory)
        environment = self.directory / '.env.mysql.local'
        first = environment.read_bytes()
        values = managed.read_environment(environment)
        self.assertNotEqual(values['DB_PASSWORD'], values['MYSQL_ROOT_PASSWORD'])
        self.assertEqual(environment.stat().st_mode & 0o777, 0o600)
        managed.prepare(self.directory)
        self.assertEqual(environment.read_bytes(), first)

    def test_initialized_volume_does_not_get_replacement_credentials(self):
        with self.assertRaises(ValueError): managed.prepare(self.directory, initialized=True)
        self.assertFalse((self.directory / '.env.mysql.local').exists())

    def test_partial_setup_is_not_given_replacement_credentials(self):
        self.directory.mkdir()
        (self.directory / 'application-local.properties').write_text('private existing config')
        with self.assertRaises(ValueError): managed.prepare(self.directory)
        self.assertFalse((self.directory / '.env.mysql.local').exists())

    def test_wrong_database_is_rejected(self):
        managed.prepare(self.directory)
        path = self.directory / '.env.mysql.local'
        path.write_text(path.read_text().replace('scheduling_managed_dev', 'scheduling_dev'))
        with self.assertRaises(ValueError): managed.read_environment(path)

    def test_backend_never_receives_root_password_or_runtime_bootstrap_password(self):
        values = {'MYSQL_ROOT_PASSWORD': 'fixture-root', 'DB_PASSWORD': 'fixture-db',
                  'ACCOUNT_BOOTSTRAP_USERNAME': 'fixture.admin', 'ACCOUNT_BOOTSTRAP_PASSWORD': 'fixture-admin'}
        run = managed.application_environment(values, 'run')
        provision = managed.application_environment(values, 'provision')
        self.assertFalse('MYSQL_ROOT_PASSWORD' in run)
        self.assertFalse('MYSQL_ROOT_PASSWORD' in provision)
        self.assertFalse('ACCOUNT_BOOTSTRAP_PASSWORD' in run)
        self.assertFalse('ACCOUNT_BOOTSTRAP_USERNAME' in run)
        self.assertEqual(provision['ACCOUNT_BOOTSTRAP_PASSWORD'], 'fixture-admin')
        self.assertEqual(run['DB_PASSWORD'], 'fixture-db')

    def test_occupied_backend_is_preserved(self):
        jar = Path(self.temp.name) / 'application.jar'
        jar.touch()
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            with self.assertRaises(ValueError): managed.application_command('run', self.directory, jar, port)
            self.assertEqual(listener.getsockname()[1], port)

    def test_closed_http_connections_do_not_block_backend_restart(self):
        jar = Path(self.temp.name) / 'application.jar'
        jar.touch()
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            with socket.create_connection(('127.0.0.1', port)) as client:
                connection, _ = listener.accept()
                connection.close()
                self.assertEqual(client.recv(1), b'')
        command = managed.application_command('run', self.directory, jar, port)
        self.assertIn('--server.port=' + str(port), command)

    def test_config_location_excludes_legacy_working_directory(self):
        jar = Path(self.temp.name) / 'application.jar'
        jar.touch()
        command = managed.application_command('provision', self.directory, jar, 8081)
        self.assertIn('--spring.config.location=classpath:/,file:' + self.directory.as_posix() + '/', command)
        self.assertIn('--spring.main.web-application-type=none', command)
        self.assertIn('--spring.profiles.active=local,schema-managed,provision-admin', command)


if __name__ == '__main__':
    unittest.main()
