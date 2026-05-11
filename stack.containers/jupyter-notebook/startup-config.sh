#!/bin/bash
set -euo pipefail

python3 <<'PY'
import json
import os
from pathlib import Path

home_dir = Path('/home/jovyan')
jupyter_config_dir = home_dir / '.jupyter'
jupyter_config_dir.mkdir(parents=True, exist_ok=True)
jupyter_config_dir.chmod(0o700)
old_umask = os.umask(0o077)

lab_theme_settings = home_dir / '.jupyter' / 'lab' / 'user-settings' / '@jupyterlab' / 'apputils-extension'
lab_theme_settings.mkdir(parents=True, exist_ok=True)
(lab_theme_settings / 'themes.jupyterlab-settings').write_text(
    json.dumps({'theme': 'JupyterLab Dark', 'theme-scrollbars': True}, indent=2) + '\n',
    encoding='utf-8',
)
(lab_theme_settings / 'themes.jupyterlab-settings').chmod(0o600)

openai_api_key = os.getenv('OPENAI_API_KEY', 'unused')

(jupyter_config_dir / 'jupyter_jupyter_ai_config.json').write_text(
    json.dumps(
        {
            'AiExtension': {
                'model_parameters': {
                    'openai-chat:qwen2.5-0.5b': {
                        'api_base': 'http://inference-gateway:8111/llm/v1',
                        'api_key': openai_api_key,
                    }
                }
            }
        },
        indent=2,
    ) + '\n',
    encoding='utf-8',
)
(jupyter_config_dir / 'jupyter_jupyter_ai_config.json').chmod(0o600)

env_values = {
    'OPENAI_API_BASE': 'http://inference-gateway:8111/llm/v1',
    'OPENAI_API_KEY': openai_api_key,
    'VLLM_API_BASE': 'http://vllm:8000/v1',
    'VLLM_API_KEY': 'unused',
    'DEFAULT_LLM_MODEL': 'qwen2.5-0.5b',
    'DEFAULT_EMBEDDING_MODEL': 'embed-small',
    'LANGCHAIN_TRACING_V2': 'false',
    'POSTGRES_HOST': os.getenv('POSTGRES_HOST', 'postgres'),
    'POSTGRES_PORT': os.getenv('POSTGRES_PORT', '5432'),
    'POSTGRES_DB': os.getenv('POSTGRES_DB', 'webservices'),
    'POSTGRES_USER': os.getenv('POSTGRES_USER', 'pipeline_user'),
    'POSTGRES_PASSWORD': os.getenv('POSTGRES_PASSWORD', ''),
}
env_file = home_dir / '.env'
env_file.write_text(
    ''.join(f'{key}={value}\n' for key, value in env_values.items()),
    encoding='utf-8',
)
env_file.chmod(0o600)
os.umask(old_umask)

notebook_dir = home_dir / 'work' / 'platform-notebooks'
notebook_dir.mkdir(parents=True, exist_ok=True)

def markdown_cell(text: str):
    return {'cell_type': 'markdown', 'metadata': {}, 'source': text.splitlines(keepends=True)}

def code_cell(text: str):
    return {'cell_type': 'code', 'execution_count': None, 'metadata': {}, 'outputs': [], 'source': text.splitlines(keepends=True)}

def write_notebook(name: str, cells):
    path = notebook_dir / name
    if path.exists():
        return
    notebook = {
        'cells': cells,
        'metadata': {
            'kernelspec': {'display_name': 'Python 3', 'language': 'python', 'name': 'python3'},
            'language_info': {'name': 'python'},
        },
        'nbformat': 4,
        'nbformat_minor': 5,
    }
    path.write_text(json.dumps(notebook, indent=1) + '\n', encoding='utf-8')

write_notebook(
    '00_platform_overview.ipynb',
    [
        markdown_cell(
            '# Platform Notebook Bootstrap\n\n'
            'This notebook image belongs to `webservices`, so it only seeds generic platform workflows.\n\n'
            'Included defaults:\n'
            '- inference-gateway / vLLM client configuration\n'
            '- optional PostgreSQL access for generic operational queries\n'
            '- no downstream domain-specific helpers\n'
        ),
        code_cell(
            'import os\n'
            'from pathlib import Path\n\n'
            'env_path = Path.home() / ".env"\n'
            'print(f"Notebook env file: {env_path}")\n'
            'print(env_path.read_text() if env_path.exists() else "No .env file found")\n'
        ),
        code_cell(
            'import os\n'
            'from openai import OpenAI\n\n'
            'client = OpenAI(base_url=os.getenv("OPENAI_API_BASE", "http://inference-gateway:8111/llm/v1"), api_key=os.getenv("OPENAI_API_KEY", "unused"))\n'
            'response = client.chat.completions.create(\n'
            '    model=os.getenv("DEFAULT_LLM_MODEL", "qwen2.5-0.5b"),\n'
            '    messages=[{"role": "user", "content": "Return exactly: platform notebook ready"}],\n'
            ')\n'
            'print(response.choices[0].message.content)\n'
        ),
    ],
)

write_notebook(
    '01_postgres_smoke.ipynb',
    [
        markdown_cell(
            '# PostgreSQL Smoke Query\n\n'
            'Use this for generic connectivity checks against the shared platform Postgres service.\n'
        ),
        code_cell(
            'import os\n'
            'import pandas as pd\n'
            'from sqlalchemy import create_engine, text\n\n'
            'pg_host = os.getenv("POSTGRES_HOST", "postgres")\n'
            'pg_port = os.getenv("POSTGRES_PORT", "5432")\n'
            'pg_db = os.getenv("POSTGRES_DB", "webservices")\n'
            'pg_user = os.getenv("POSTGRES_USER", "pipeline_user")\n'
            'pg_password = os.getenv("POSTGRES_PASSWORD", "")\n'
            'engine = create_engine(f"postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}")\n'
            'with engine.connect() as conn:\n'
            '    df = pd.read_sql(text("SELECT current_timestamp AS now, current_database() AS database_name"), conn)\n'
            'df\n'
        ),
    ],
)
PY

chown -R "${NB_UID:-1000}:${NB_GID:-100}" /home/jovyan/work/platform-notebooks 2>/dev/null || true

echo 'Jupyter notebook environment configured for generic platform workflows'
