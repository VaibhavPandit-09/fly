CREATE TABLE IF NOT EXISTS roots (
  id INTEGER PRIMARY KEY,
  path TEXT NOT NULL UNIQUE,
  priority INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS directories (
  id INTEGER PRIMARY KEY,
  basename TEXT NOT NULL,
  fullpath TEXT NOT NULL UNIQUE,
  depth INTEGER NOT NULL,
  root_id INTEGER NOT NULL REFERENCES roots(id) ON DELETE CASCADE,
  mtime INTEGER NOT NULL,
  last_used INTEGER,
  segments TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_dirs_basename
  ON directories (basename);

CREATE INDEX IF NOT EXISTS idx_dirs_root_basename
  ON directories (root_id, basename);
