#!/usr/bin/env node
// qoder ACP bridge build-input fingerprint (F31). No dependencies.
//
// The bridge dist/ is git-ignored, so a checkout can carry a dist built from older
// sources (a merge that touches src/ without rebuilding). mtime alone misses this when
// a checkout tool rewrites timestamps; this helper hashes the actual build inputs and
// records them in dist/.build-inputs (inside the git-ignored dist).
//
// Inputs: every file under src/ recursively, plus package.json, tsconfig.json and
// pnpm-lock.yaml when present. Paths are recorded with forward slashes so a manifest
// written on Windows still matches on POSIX and vice versa.
//
// Usage:
//   node scripts/lib/bridge-freshness.mjs check <bridgeDir>
//   node scripts/lib/bridge-freshness.mjs write <bridgeDir>
//
// Exit codes (stable contract; the start scripts and self-tests rely on them):
//   0  check: dist/main.js exists and matches the recorded inputs (fresh)
//      write: the manifest was written
//   1  check: a problem; the reason is printed on stdout
//      write: no manifest was written (dist/main.js is missing)
//   2  usage error, or a filesystem error the helper cannot interpret
//
// A missing (or unreadable, or older-version) manifest is the documented legacy state:
// check falls back to comparing file mtimes against dist/main.js.

import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

const MANIFEST_NAME = ".build-inputs";
const MANIFEST_VERSION = 1;
const ROOT_INPUTS = ["package.json", "tsconfig.json", "pnpm-lock.yaml"];

function sha256(data) {
  return createHash("sha256").update(data).digest("hex");
}

function isFile(target) {
  try {
    return statSync(target).isFile();
  } catch {
    return false;
  }
}

function isDirectory(target) {
  try {
    return statSync(target).isDirectory();
  } catch {
    return false;
  }
}

// Collects { rel, abs } for every input, with posix-style relative paths.
function collectInputs(bridgeDir) {
  const inputs = [];
  const walk = (dir, relBase) => {
    const entries = readdirSync(dir, { withFileTypes: true });
    entries.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
    for (const entry of entries) {
      const abs = path.join(dir, entry.name);
      const rel = relBase + "/" + entry.name;
      if (entry.isDirectory()) {
        walk(abs, rel);
      } else if (entry.isFile()) {
        inputs.push({ rel, abs });
      }
    }
  };

  const srcDir = path.join(bridgeDir, "src");
  if (isDirectory(srcDir)) {
    walk(srcDir, "src");
  }
  for (const name of ROOT_INPUTS) {
    const abs = path.join(bridgeDir, name);
    if (isFile(abs)) {
      inputs.push({ rel: name, abs });
    }
  }
  return inputs.sort((a, b) => (a.rel < b.rel ? -1 : a.rel > b.rel ? 1 : 0));
}

// { hash, files: { rel: sha256 } }; the combined hash covers the sorted rel + digest list.
function snapshot(bridgeDir) {
  const files = {};
  for (const input of collectInputs(bridgeDir)) {
    files[input.rel] = sha256(readFileSync(input.abs));
  }
  const listing = Object.keys(files)
    .sort()
    .map((rel) => rel + " " + files[rel])
    .join("\n");
  return { hash: sha256(listing), files };
}

// Names the inputs that differ from the recorded manifest ("changed state").
function describeMismatch(recorded, current) {
  const names = [...new Set([...Object.keys(recorded), ...Object.keys(current)])].sort();
  const changed = names.filter((name) => recorded[name] !== current[name]);
  return changed.length > 0 ? changed.join(", ") : "recorded digest mismatch";
}

// Legacy fallback: any input newer than dist/main.js. Returns posix rel names.
function newerThanDist(bridgeDir, distMain) {
  const distTime = statSync(distMain).mtimeMs;
  const newer = [];
  const walk = (dir, relBase) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, entry.name);
      const rel = relBase + "/" + entry.name;
      if (entry.isDirectory()) {
        walk(abs, rel);
      } else if (entry.isFile() && statSync(abs).mtimeMs > distTime) {
        newer.push(rel);
      }
    }
  };

  const srcDir = path.join(bridgeDir, "src");
  if (isDirectory(srcDir)) {
    walk(srcDir, "src");
  }
  for (const name of ROOT_INPUTS) {
    const abs = path.join(bridgeDir, name);
    if (isFile(abs) && statSync(abs).mtimeMs > distTime) {
      newer.push(name);
    }
  }
  return newer;
}

function readManifest(manifestPath) {
  if (!isFile(manifestPath)) {
    return null;
  }
  try {
    const parsed = JSON.parse(readFileSync(manifestPath, "utf8"));
    if (
      parsed &&
      parsed.version === MANIFEST_VERSION &&
      typeof parsed.hash === "string" &&
      parsed.files &&
      typeof parsed.files === "object"
    ) {
      return parsed;
    }
  } catch {
    // An unreadable manifest is the legacy state, not a crash.
  }
  return null;
}

function check(bridgeDir) {
  const distMain = path.join(bridgeDir, "dist", "main.js");
  if (!isFile(distMain)) {
    return { code: 1, reason: `dist/main.js is missing under '${bridgeDir}'` };
  }

  const manifest = readManifest(path.join(bridgeDir, "dist", MANIFEST_NAME));
  if (manifest) {
    const current = snapshot(bridgeDir);
    if (manifest.hash === current.hash) {
      return { code: 0, reason: "" };
    }
    return {
      code: 1,
      reason:
        "bridge inputs changed since the last build: " +
        describeMismatch(manifest.files, current.files),
    };
  }

  // Missing/unusable manifest: the documented legacy mtime comparison.
  const newer = newerThanDist(bridgeDir, distMain);
  if (newer.length > 0) {
    return {
      code: 1,
      reason:
        "dist/main.js is older than: " +
        newer.join(", ") +
        " (legacy mtime check: no build manifest)",
    };
  }
  return { code: 0, reason: "" };
}

function write(bridgeDir) {
  const distMain = path.join(bridgeDir, "dist", "main.js");
  if (!isFile(distMain)) {
    return { code: 1, reason: `dist/main.js is missing under '${bridgeDir}'` };
  }
  const current = snapshot(bridgeDir);
  const manifest = {
    version: MANIFEST_VERSION,
    algorithm: "sha256",
    hash: current.hash,
    files: current.files,
  };
  writeFileSync(
    path.join(bridgeDir, "dist", MANIFEST_NAME),
    JSON.stringify(manifest, null, 2) + "\n"
  );
  return { code: 0, reason: "" };
}

function main(argv) {
  const [command, bridgeDir] = argv;
  if (!command || !bridgeDir || (command !== "check" && command !== "write")) {
    process.stdout.write("usage: node bridge-freshness.mjs <check|write> <bridgeDir>\n");
    return 2;
  }

  let result;
  try {
    const resolved = path.resolve(bridgeDir);
    result = command === "check" ? check(resolved) : write(resolved);
  } catch (error) {
    process.stderr.write(
      "bridge-freshness: " + (error && error.message ? error.message : String(error)) + "\n"
    );
    return 2;
  }

  if (result.reason) {
    process.stdout.write(result.reason + "\n");
  }
  return result.code;
}

// Set the exit code and let the event loop drain: process.exit() can truncate the
// reason already written to a piped stdout, and the callers parse that reason.
process.exitCode = main(process.argv.slice(2));
