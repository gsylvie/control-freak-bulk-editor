# control-freak-bulk-editor

A tool to quickly apply [Control Freak](https://marketplace.atlassian.com/apps/1217635/control-freak-commit-checkers-and-jira-hooks-for-bitbucket?hosting=datacenter&tab=overview) config overrides to a subset
of your Bitbucket Data Center repositories.

## Sample "bulkedits.json"

```
{
  "commitPolicyJirasOn": "Hdpbfhrto",
  "commitMustMatchRegex": "yes",
  "commitRegex": ".*GENAI = (YES|NO).*",
  "commitRegexMiss": "It is mandatory to include GENAI = YES or GENAI = NO in your commit message",
  "repos": [
    "BAN/b",
    "BAN/c",
    "BAN/d",
    "PROJECT_1/rep_1"
  ]
}
```

This "bulkedits.json" file will set values for those 4 config keys in the repositories listed.  Repositories can be specified using "PROJECT/repo" slug style (e.g., "PROJECT_1/rep_1"), or if
you happen to know a repository's Integer id, you can use that instead (e.g., "342").

In this particular example the bulk-edit will setup a regex rule that all commits must pass.

Before:

![image](https://github.com/gsylvie/control-freak-bulk-editor/assets/17037724/48397359-1ec8-418c-8a5e-c5862d00ccdf)

After:

![image](https://github.com/gsylvie/control-freak-bulk-editor/assets/17037724/518935a3-5787-4beb-8da2-46173b42a61a)

## How It Chooses Values For Unspecified Keys

As expected, this tool will apply the value specified for each key in the "bulkedits.json" file.

Recall that each key is a member of a single group.  For example, there is the "General Keys" group, the "Jira Keys" group, the "Jira Advanced Keys" group, etc.  You can see the official key
grouping by taking a look at Control-Freak's REST endpoint at any time.  I've also included an extract of the grouping here at the bottom of this README.md.

With this grouping in mind, how the tool decides which value to apply for the remaining keys (the keys not mentioned in the "bulkedits.json" file) can be a little complicated.  There are two cases:

- Case A:  The unmentioned key is related to some of the mentioned keys. In this case the tool will go up the hierarchy.

- Case B:  The unmentioned key is not related (not in the same group) as any of the other mentioned keys.  In this case the tool will leave the key's value alone (will leave it set to whatever value it already had).



## Steps to use:

1. Compile the code:  mvn clean package 

2. Obtain a HTTP Authorization Token from Bitbucket and save it to ".controlFreak.tok"

3. Edit bulkdedits.json to prepare your bulk-edit !

4. Run the code:

```
java -jar ./target/control-freak-bulk-editor-2023.11.09.jar  <USER> <BITBUCKET-URL> bulkedits.json
```

Note:  The \<USER\> must correspond to the HTTP Token saved during step #2,
and must have "admin" permission to the repositories you plan to bulk-edit
Control-Freak's settings on.

Example run:

```
$ java -cp . ControlFreakBulkUpdate admin  http://localhost:7990/bitbucket bulkedits.json 
2023-11-09T01:32:04.574-0800 ! Unknown keys (ignoring for bulk-edit): [bobEatsYummySushi]
2023-11-09T01:32:04.574-0800 - Keys to bulk-edit: [commitMustMatchRegex, commitPolicyJirasOn, commitRegex, commitRegexMiss]
2023-11-09T01:32:04.575-0800 - Groups to overwrite: [o_jira]
2023-11-09T01:32:04.575-0800 - Keys to include (because from same group(s)):   [commitIgnoreMerges, commitIgnorePattern, commitIgnorePatternIsPrefix, commitIgnorePatternText, commitIgnoreRebases, commitIgnoreSubmodules, commitMustRefJira]
2023-11-09T01:32:04.907-0800 - ControlFreakBulkUpdate confirmed edits will work.  Proceeding with bulk-update...
2023-11-09T01:32:04.994-0800 - Saved config backup: /home/julius/IdeaProjects/project/src/main/java/cf_backups/BAN_b-backup-2023-11-09.013204.json
2023-11-09T01:32:05.047-0800 - Bulk-edit for BAN/b - result: {"status":"SUCCESS - POST accepted and applied by Control-Freak config-handler"}
2023-11-09T01:32:05.131-0800 - Saved config backup: /home/julius/IdeaProjects/project/src/main/java/cf_backups/BAN_c-backup-2023-11-09.013205.json
2023-11-09T01:32:05.183-0800 - Bulk-edit for BAN/c - result: {"status":"SUCCESS - POST accepted and applied by Control-Freak config-handler"}
2023-11-09T01:32:05.269-0800 - Saved config backup: /home/julius/IdeaProjects/project/src/main/java/cf_backups/BAN_d-backup-2023-11-09.013205.json
2023-11-09T01:32:05.325-0800 - Bulk-edit for BAN/d - result: {"status":"SUCCESS - POST accepted and applied by Control-Freak config-handler"}
2023-11-09T01:32:05.412-0800 - Saved config backup: /home/julius/IdeaProjects/project/src/main/java/cf_backups/PROJECT_1_rep_1-backup-2023-11-09.013205.json
2023-11-09T01:32:05.471-0800 - Bulk-edit for PROJECT_1/rep_1 - result: {"status":"SUCCESS - POST accepted and applied by Control-Freak config-handler"}
```

## Requirements:

Java JDK 8 or newer (the JDK includes the "javac" command).

That's all it requires. We carefully wrote this 1300 line Java file to avoid
the need for any 3rd party or open-source libraries. It has zero dependencies.
