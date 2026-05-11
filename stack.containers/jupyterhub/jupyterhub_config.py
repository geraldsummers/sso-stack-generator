import os
import sys
import ssl
import re

from jupyterhub.handlers import BaseHandler
from remote_user_authenticator import RemoteUserAuthenticator
from tornado import web
from traitlets import Set, Unicode

from tornado.httpclient import AsyncHTTPClient
AsyncHTTPClient.configure("tornado.simple_httpclient.SimpleAsyncHTTPClient")

c = get_config()


class webservicesRemoteUserLoginHandler(BaseHandler):
    async def get(self):
        auth_model = await self.authenticator.authenticate(self, None)
        if auth_model is None:
            raise web.HTTPError(401)

        username = self.authenticator.normalize_username(auth_model.get('name', ''))
        if not username or not self.authenticator.validate_username(username):
            raise web.HTTPError(403)

        auth_model['name'] = username

        if not self.authenticator.allow_all and not self.authenticator.check_allowed(username, auth_model):
            raise web.HTTPError(403)

        user = await self.auth_to_user(auth_model)
        self.set_login_cookie(user)
        self.redirect(self.get_next_url(user))


class webservicesRemoteUserAuthenticator(RemoteUserAuthenticator):
    header_name = Unicode(
        default_value='Remote-User',
        config=True,
        help="HTTP header that carries the authenticated username."
    )
    groups_header_name = Unicode(
        default_value='Remote-Groups',
        config=True,
        help="HTTP header that carries Keycloak group membership."
    )
    allowed_remote_groups = Set(
        config=True,
        help="Remote groups allowed to access JupyterHub."
    )

    def get_handlers(self, app):
        return [
            (r'/login', webservicesRemoteUserLoginHandler),
        ]

    async def authenticate(self, handler, data=None):
        remote_user = handler.request.headers.get(self.header_name, '').strip()
        if not remote_user:
            return None

        raw_groups = handler.request.headers.get(self.groups_header_name, '')
        groups = sorted({
            group.strip().lower()
            for group in re.split(r'[\s,;|]+', raw_groups)
            if group.strip()
        })

        return {
            'name': remote_user,
            'groups': groups,
        }

    def check_allowed(self, username, authentication=None):
        if username in self.allowed_users:
            return True

        configured_groups = {group.strip().lower() for group in self.allowed_remote_groups if group.strip()}
        if not configured_groups:
            return True

        auth_groups = {
            group.strip().lower()
            for group in (authentication or {}).get('groups', [])
            if isinstance(group, str) and group.strip()
        }
        return not auth_groups.isdisjoint(configured_groups)

c.JupyterHub.bind_url = 'http://0.0.0.0:8000'
c.JupyterHub.hub_bind_url = 'http://0.0.0.0:8081'
c.JupyterHub.hub_connect_url = 'http://jupyterhub:8081'

c.JupyterHub.spawner_class = 'dockerspawner.DockerSpawner'

c.DockerSpawner.image = os.environ.get('JUPYTER_NOTEBOOK_IMAGE', 'platform-jupyter-notebook:5.4.3')

c.DockerSpawner.network_name = os.environ.get('DOCKER_NETWORK_NAME', 'webservices_ai')

c.DockerSpawner.cmd = 'start-singleuser.py'
NOTEBOOK_UID = int(os.environ.get('JUPYTER_NOTEBOOK_UID', '1000'))
NOTEBOOK_GID = int(os.environ.get('JUPYTER_NOTEBOOK_GID', '100'))
NOTEBOOK_WORKDIR = '/home/jovyan/work'
c.DockerSpawner.extra_create_kwargs = {
    'user': f'{NOTEBOOK_UID}:{NOTEBOOK_GID}',
}

c.DockerSpawner.notebook_dir = NOTEBOOK_WORKDIR

c.Spawner.default_url = '/lab'

c.DockerSpawner.remove = True

c.Spawner.start_timeout = 300
c.Spawner.http_timeout = 300
c.DockerSpawner.pull_policy = 'ifnotpresent'

c.DockerSpawner.volumes = {
    'jupyterhub-user-{username}': NOTEBOOK_WORKDIR
}


async def ensure_singleuser_volume_permissions(spawner):
    await spawner.pull_image(spawner.image)

    target_binds = {
        source: bind
        for source, bind in spawner.volume_binds.items()
        if bind.get('bind') == NOTEBOOK_WORKDIR
    }
    if not target_binds:
        return

    bootstrap_container_id = None
    try:
        host_config = spawner.client.create_host_config(binds=target_binds)
        bootstrap = spawner.client.create_container(
            image=spawner.image,
            command=[
                'bash',
                '-lc',
                (
                    f'mkdir -p {NOTEBOOK_WORKDIR} && '
                    f'chown -R {NOTEBOOK_UID}:{NOTEBOOK_GID} {NOTEBOOK_WORKDIR}'
                ),
            ],
            host_config=host_config,
            volumes=[NOTEBOOK_WORKDIR],
            user='root',
        )
        bootstrap_container_id = bootstrap.get('Id')
        spawner.client.start(bootstrap_container_id)
        wait_result = spawner.client.wait(bootstrap_container_id)
        status_code = wait_result.get('StatusCode', 1) if isinstance(wait_result, dict) else int(wait_result)
        if status_code != 0:
            logs = spawner.client.logs(bootstrap_container_id).decode('utf-8', errors='replace')
            raise RuntimeError(f'volume ownership bootstrap failed ({status_code}): {logs}')
    finally:
        if bootstrap_container_id:
            spawner.client.remove_container(bootstrap_container_id, v=True, force=True)


c.Spawner.pre_spawn_hook = ensure_singleuser_volume_permissions

openai_api_key = os.environ.get('OPENAI_API_KEY', 'unused')

c.Spawner.environment = {
    'OPENAI_API_BASE': 'http://inference-gateway:8111/llm/v1',
    'OPENAI_API_KEY': openai_api_key,
    'POSTGRES_HOST': os.environ.get('POSTGRES_HOST', 'postgres'),
    'POSTGRES_PORT': os.environ.get('POSTGRES_PORT', '5432'),
    'POSTGRES_DB': os.environ.get('POSTGRES_DB', 'webservices'),
    'POSTGRES_USER': os.environ.get('POSTGRES_USER', 'pipeline_user'),
    'POSTGRES_PASSWORD': os.environ.get('POSTGRES_PASSWORD', ''),
}

c.JupyterHub.authenticator_class = webservicesRemoteUserAuthenticator

c.webservicesRemoteUserAuthenticator.groups_header_name = 'Remote-Groups'
c.webservicesRemoteUserAuthenticator.allowed_remote_groups = {
    group.strip()
    for group in re.split(r'[\s,;|]+', os.environ.get('JUPYTERHUB_ALLOWED_REMOTE_GROUPS', 'users,admins'))
    if group.strip()
}

c.Authenticator.manage_groups = True
c.Authenticator.allow_all = False
c.Authenticator.any_allow_config = True

c.Authenticator.admin_users = set()

c.JupyterHub.log_level = 'INFO'
c.Authenticator.enable_auth_state = False

c.JupyterHub.cookie_secret_file = '/srv/jupyterhub/jupyterhub_cookie_secret'

c.JupyterHub.db_url = 'sqlite:////srv/jupyterhub/jupyterhub.sqlite'

c.JupyterHub.allow_named_servers = True
c.JupyterHub.named_server_limit_per_user = 3
