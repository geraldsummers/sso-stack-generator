FROM jupyter/minimal-notebook@sha256:1c4c8b6c7c27059c353d4e80523c2696e34723fde67d27418873ebeb42032551

ARG KOTLIN_COMPILER_SHA256=b6698d5728ad8f9edcdd01617d638073191d8a03139cc538a391b4e3759ad297
ARG POSTGRES_JDBC_SHA256=69020b3bd20984543e817393f2e6c01a890ef2e37a77dd11d6d8508181d079ab
ARG GSON_JAR_SHA256=57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b

USER root

RUN apt-get update && \
    apt-get install -y curl gnupg build-essential python3 && \
    install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg && \
    chmod a+r /etc/apt/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN apt-get update && \
    apt-get install -y openjdk-17-jdk wget unzip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN cd /opt && \
    wget -q https://github.com/JetBrains/kotlin/releases/download/v2.1.0/kotlin-compiler-2.1.0.zip && \
    echo "${KOTLIN_COMPILER_SHA256}  kotlin-compiler-2.1.0.zip" | sha256sum -c - && \
    unzip kotlin-compiler-2.1.0.zip && \
    rm kotlin-compiler-2.1.0.zip && \
    ln -s /opt/kotlinc/bin/kotlin /usr/local/bin/kotlin && \
    ln -s /opt/kotlinc/bin/kotlinc /usr/local/bin/kotlinc

RUN npm install -g --unsafe-perm ijavascript && \
    ijsinstall --install=global

USER ${NB_UID}

RUN pip install --no-cache-dir kotlin-jupyter-kernel && \
    python -m kotlin_kernel add-kernel

USER root

RUN pip install --no-cache-dir \
    'openai>=1.0.0' \
    'python-dotenv>=1.0.0' \
    'httpx>=0.27.0' \
    'pydantic>=2.0.0' \
    'tiktoken>=0.7.0' \
    'jupyterhub==5.4.3' \
    'pandas>=2.2.0' \
    'numpy>=1.26.0' \
    'sqlalchemy>=2.0.0' \
    'psycopg2-binary>=2.9.0' \
    'matplotlib>=3.8.0' \
    'seaborn>=0.13.0' \
    'scipy>=1.11.0'

# Keep notebook AI tooling on the Jupyter AI 2.x / LangChain 0.3.x compatibility line.
RUN pip install --no-cache-dir \
    'jupyter-ai==2.31.7' \
    'langchain-openai==0.3.35'

RUN pip install --no-cache-dir \
    'llama-index>=0.11.0' \
    'llama-index-llms-openai>=0.2.0' \
    'llama-index-embeddings-openai>=0.2.0'

RUN pip install --no-cache-dir \
    'autogen-agentchat>=0.4.0' \
    'autogen-ext[openai]>=0.4.0'

RUN pip install --no-cache-dir \
    'chromadb>=0.5.0' \
    'faiss-cpu>=1.8.0'

RUN mkdir -p /opt/webservices/jars && \
    curl -fsSL -o /opt/webservices/jars/postgresql-42.7.5.jar \
      https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar && \
    echo "${POSTGRES_JDBC_SHA256}  /opt/webservices/jars/postgresql-42.7.5.jar" | sha256sum -c - && \
    curl -fsSL -o /opt/webservices/jars/gson-2.11.0.jar \
      https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar && \
    echo "${GSON_JAR_SHA256}  /opt/webservices/jars/gson-2.11.0.jar" | sha256sum -c -

RUN pip install --no-cache-dir 'packaging<26.0.0' && \
    pip check

RUN mkdir -p /usr/local/share/jupyter/lab/settings && \
    printf '%s\n' \
      '{' \
	      '  "@jupyterlab/apputils-extension:themes": {' \
	      '    "theme": "JupyterLab Dark",' \
	      '    "theme-scrollbars": true' \
	      '  }' \
      '}' \
      > /usr/local/share/jupyter/lab/settings/overrides.json

WORKDIR /home/jovyan

COPY --chown=${NB_UID}:${NB_GID} stack.containers/jupyter-notebook/startup-config.sh /usr/local/bin/startup-config.sh
RUN chmod +x /usr/local/bin/startup-config.sh

RUN jupyter kernelspec list

RUN mkdir -p /usr/local/bin/start-notebook.d && \
    echo '#!/bin/bash' > /usr/local/bin/start-notebook.d/configure-lm-agents.sh && \
    echo '/usr/local/bin/startup-config.sh' >> /usr/local/bin/start-notebook.d/configure-lm-agents.sh && \
    chmod +x /usr/local/bin/start-notebook.d/configure-lm-agents.sh

# Prebuild JupyterLab assets to avoid slow first-start builds per user.
RUN jupyter lab build --minimize=False && \
    jupyter lab clean

# Root-level installs above leave parts of ~/.local unreadable to the notebook user.
# Fix ownership explicitly so nbconvert/Jupyter config discovery works in one-off runs
# and in JupyterHub-spawned servers.
RUN mkdir -p /home/jovyan/.local/share/jupyter /home/jovyan/.ipython && \
    chown -R ${NB_UID}:${NB_GID} /home/jovyan/.local /home/jovyan/.ipython && \
    chmod -R u+rwX,g+rX /home/jovyan/.local /home/jovyan/.ipython

USER ${NB_UID}
WORKDIR /home/jovyan
