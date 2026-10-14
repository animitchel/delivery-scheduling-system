#!/usr/bin/env python3
"""Prepare and run a separate managed development database without overwriting old setup."""
import argparse
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = {'DB_NAME': 'scheduling_managed_dev', 'DB_USER': 'scheduling_managed_app', 'DB_PORT': '3308'}


def read_environment(path):
    values = {}
    for line in path.read_text().splitlines():
        if line and not line.startswith('#'):
            key, separator, value = line.partition('=')
            if not separator or key in values:
                raise ValueError('Invalid private managed environment file.')
            values[key] = value
    for key, value in DEFAULTS.items():
        if values.get(key) != value:
            raise ValueError('Managed database identity differs from the dedicated local setup.')
    for key in ('DB_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'ACCOUNT_BOOTSTRAP_USERNAME', 'ACCOUNT_BOOTSTRAP_PASSWORD'):
        if not values.get(key):
            raise ValueError('Managed environment is incomplete; do not regenerate credentials for an initialized volume.')
    return values


def write_private(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(content)


def prepare(directory, initialized=False):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    environment = directory / '.env.mysql.local'
    configuration = directory / 'application-local.properties'
    if not environment.exists():
        if initialized:
            raise ValueError('Managed volume exists without its credentials. Recover the original private files; no replacements were created.')
        # Never invent replacement credentials if a partial setup already exists.
        if configuration.exists():
            raise ValueError('Configuration exists without credentials. Recover its private environment file first.')
        values = {**DEFAULTS, 'DB_PASSWORD': secrets.token_urlsafe(24),
                  'MYSQL_ROOT_PASSWORD': secrets.token_urlsafe(24),
                  'ACCOUNT_BOOTSTRAP_USERNAME': 'managed.admin',
                  'ACCOUNT_BOOTSTRAP_PASSWORD': secrets.token_urlsafe(24)}
        write_private(environment, ''.join(f'{key}={value}\n' for key, value in values.items()))
    read_environment(environment)
    if not configuration.exists():
        write_private(configuration, 'spring.datasource.url=jdbc:mysql://127.0.0.1:${DB_PORT}/${DB_NAME}\n'
                      'spring.datasource.username=${DB_USER}\nspring.datasource.password=${DB_PASSWORD}\n'
                      'spring.jpa.hibernate.ddl-auto=validate\nserver.address=127.0.0.1\nserver.port=8081\n')
    # Existing files are retained, including private changes; no secrets are printed.



def compose(command, directory):
    return ['docker', 'compose', '--project-name', 'delivery-scheduling-managed',
            '--env-file', str(directory / '.env.mysql.local'), '-f', str(ROOT / 'compose.mysql.managed.yaml'), *command]


def application_command(action, directory, jar, port):
    if not jar.is_file():
        raise ValueError('Build the verified application JAR first; no database action was performed.')
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    profiles = 'local,schema-managed' + (',provision-admin' if action == 'provision' else '')
    command = [java, '-jar', str(jar), '--spring.profiles.active=' + profiles,
               '--spring.config.location=classpath:/,file:' + directory.as_posix() + '/',
               '--spring.jpa.hibernate.ddl-auto=validate', '--spring.flyway.enabled=true',
               '--spring.datasource.url=jdbc:mysql://127.0.0.1:3308/scheduling_managed_dev',
               '--spring.datasource.username=scheduling_managed_app',
               '--spring.datasource.password=${DB_PASSWORD}', '--server.address=127.0.0.1']
    if action == 'provision':
        command.append('--spring.main.web-application-type=none')
    else:
        # Check before starting; leave an existing backend process alone.
        with socket.socket() as listener:
            # Permit a clean restart after closed HTTP connections enter TIME_WAIT.
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                listener.bind(('127.0.0.1', port))
            except OSError as failure:
                raise ValueError('Backend port is in use. Stop the other backend or choose --port; it was not stopped.') from failure
        command.append('--server.port=' + str(port))
    return command


def application_environment(values, action):
    environment = {**os.environ, **values}
    environment.pop('MYSQL_ROOT_PASSWORD', None)
    if action == 'run':
        environment.pop('ACCOUNT_BOOTSTRAP_USERNAME', None)
        environment.pop('ACCOUNT_BOOTSTRAP_PASSWORD', None)
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'up', 'status', 'stop', 'provision', 'run'])
    parser.add_argument('--config-dir', type=Path, default=ROOT / '.local/managed')
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/schedulingApp-0.0.1-SNAPSHOT.jar')
    parser.add_argument('--port', type=int, default=8081)
    args = parser.parse_args()
    directory = args.config_dir.resolve()
    if not 1 <= args.port <= 65535:
        raise ValueError('Choose a valid backend port.')
    if args.action == 'prepare':
        volumes = subprocess.run(['docker', 'volume', 'ls', '--filter',
                                  'name=^delivery-scheduling-managed_managed_mysql_data$', '--format', '{{.Name}}'],
                                 capture_output=True, text=True, check=True)
        prepare(directory, initialized=bool(volumes.stdout.strip()))
        print('Private managed configuration is ready.')
        return
    values = read_environment(directory / '.env.mysql.local')
    if args.action in ('up', 'status', 'stop'):
        commands = {'up': ['up', '-d', '--wait'], 'status': ['ps'], 'stop': ['stop']}
        subprocess.run(compose(commands[args.action], directory), cwd=ROOT, env={**os.environ, **values}, check=True)
    else:
        environment = application_environment(values, args.action)
        command = application_command(args.action, directory, args.jar.resolve(), args.port)
        # Replace this process, preserving normal shutdown signals and avoiding orphaned Java.
        os.execvpe(command[0], command, environment)


if __name__ == '__main__':
    try:
        main()
    except ValueError as failure:
        print(str(failure), file=sys.stderr)
        sys.exit(1)
    except (OSError, subprocess.CalledProcessError):
        # Avoid exception details from config/subprocesses that might contain credentials.
        print('Managed local setup failed. Check private configuration, Docker/JDK, artifact and port availability; existing files/data were preserved.', file=sys.stderr)
        sys.exit(1)
