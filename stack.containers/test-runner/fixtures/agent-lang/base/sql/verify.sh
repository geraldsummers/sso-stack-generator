#!/usr/bin/env bash
set -euo pipefail
rm -f test.db
sqlite3 test.db <<'SQL'
create table demo (value text not null);
insert into demo(value) values ('SQL_OK');
.output output.txt
select value from demo;
SQL
grep -qx 'SQL_OK' output.txt
