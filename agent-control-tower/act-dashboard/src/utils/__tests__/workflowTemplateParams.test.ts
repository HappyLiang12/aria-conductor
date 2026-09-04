import { describe, it, expect } from 'vitest';
import { extractTemplateParams } from '../workflowTemplateParams';

describe('extractTemplateParams', () => {
  it('extracts params from inline prompt_template values', () => {
    const yaml = `steps:
  - kind: ba
    prompt_template: "Analyze issue {issueRef} in {issueRepo}"
    max_iterations: 15`;
    expect(extractTemplateParams(yaml)).toEqual(['issueRef', 'issueRepo']);
  });

  it('excludes system placeholders', () => {
    const yaml = `steps:
  - kind: dev
    prompt_template: "Use {previousOutput} and {specRef} on {branchName} with {repoUrl}"`;
    expect(extractTemplateParams(yaml)).toEqual(['repoUrl']);
  });

  it('handles block scalar prompt_template', () => {
    const yaml = `steps:
  - kind: ba
    prompt_template: |
      Analyze issue {issueRef} from repo {issueRepo}.
      Clone with {repoUrl} and check branch.
    max_iterations: 15`;
    expect(extractTemplateParams(yaml)).toEqual(['issueRef', 'issueRepo', 'repoUrl']);
  });

  it('ignores params outside prompt_template fields', () => {
    const yaml = `# This template uses {notAParam} in a comment
name: my-workflow
description: "Uses {alsoNotParam}"
steps:
  - kind: ba
    agent_role: ba
    prompt_template: "Real {issueRef} here"`;
    expect(extractTemplateParams(yaml)).toEqual(['issueRef']);
  });

  it('deduplicates params across multiple steps', () => {
    const yaml = `steps:
  - kind: dev
    prompt_template: "Clone {repoUrl} branch {branchName}"
  - kind: qa
    prompt_template: "Verify {repoUrl} against spec"`;
    // branchName is a system placeholder, repoUrl appears twice
    expect(extractTemplateParams(yaml)).toEqual(['repoUrl']);
  });

  it('returns sorted results', () => {
    const yaml = `steps:
  - prompt_template: "{zebra} and {alpha} and {middle}"`;
    expect(extractTemplateParams(yaml)).toEqual(['alpha', 'middle', 'zebra']);
  });

  it('returns empty array for empty/null input', () => {
    expect(extractTemplateParams('')).toEqual([]);
  });

  it('returns empty array for yaml with no prompt_template', () => {
    const yaml = `steps:
  - kind: ba
    agent_role: ba
    max_iterations: 15`;
    expect(extractTemplateParams(yaml)).toEqual([]);
  });

  it('handles underscored param names', () => {
    const yaml = `steps:
  - prompt_template: "Use {my_param} and {_private}"`;
    expect(extractTemplateParams(yaml)).toEqual(['_private', 'my_param']);
  });

  it('ignores malformed placeholders', () => {
    const yaml = `steps:
  - prompt_template: "Has {123numeric} and {has space} and {valid_one}"`;
    expect(extractTemplateParams(yaml)).toEqual(['valid_one']);
  });

  it('keeps params after a # inside a double-quoted prompt_template (literal content)', () => {
    const yaml = `name: demo
steps:
  - agent_role: dev
    prompt_template: "fix {issueRepo} #see {designNote}"`;
    expect(extractTemplateParams(yaml)).toEqual(['designNote', 'issueRepo']);
  });

  it('keeps params after a # inside a single-quoted prompt_template', () => {
    const yaml = `steps:
  - prompt_template: 'fix {issueRepo} #see {designNote}'`;
    expect(extractTemplateParams(yaml)).toEqual(['designNote', 'issueRepo']);
  });

  it('strips inline comments only from plain (unquoted) prompt_template values', () => {
    const yaml = `steps:
  - prompt_template: Analyze {issueRef} # verify {ghostParam}`;
    expect(extractTemplateParams(yaml)).toEqual(['issueRef']);
  });

  it('keeps quoted content and strips the comment after the closing quote', () => {
    const yaml = `steps:
  - prompt_template: "Use {issueRef}" # this {ghostParam} is a comment`;
    expect(extractTemplateParams(yaml)).toEqual(['issueRef']);
  });

  it('treats #-leading lines inside a block scalar as literal content, not comments', () => {
    const yaml = `steps:
  - prompt_template: |
      Analyze {issueRef}.
      # check {designNote} next
    max_iterations: 5`;
    expect(extractTemplateParams(yaml)).toEqual(['designNote', 'issueRef']);
  });

  it('still skips #-leading comment lines between fields', () => {
    const yaml = `steps:
  # {ghostParam} lives in a comment
  - prompt_template: "Real {issueRef}"`;
    expect(extractTemplateParams(yaml)).toEqual(['issueRef']);
  });
});
