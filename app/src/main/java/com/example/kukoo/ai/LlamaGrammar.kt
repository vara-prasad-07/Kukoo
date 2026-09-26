package com.example.kukoo.ai

/**
 * GBNF grammar (llama.cpp) for the flat JSON command object [LlamaIntentParser] asks the model for.
 * With it, the sampler can only emit an object whose "action" is one of the supported commands
 * (add_task, answer, discard_task, query_tasks, update_task, complete_task, reopen_task, delete_task, replan, snooze, undo,
 * end_call, unsupported) and whose other keys use the allowed value sets. Free text is limited to
 * title / target / new_title / notes strings.
 */
object LlamaGrammar {
    val COMMAND_JSON: String = """
root ::= "{" ws "\"action\"" ws ":" ws action (ws "," ws member)* ws "}"

action ::= "\"add_task\"" | "\"answer\"" | "\"discard_task\"" | "\"query_tasks\"" | "\"update_task\"" | "\"complete_task\"" | "\"reopen_task\""
         | "\"delete_task\"" | "\"replan\"" | "\"snooze\"" | "\"undo\"" | "\"end_call\"" | "\"unsupported\""

member ::= title | target | newtitle | scope | day | time | clear | duration | priority | recurrence | notes

title      ::= "\"title\"" ws ":" ws string
target     ::= "\"target\"" ws ":" ws string
newtitle   ::= "\"new_title\"" ws ":" ws string
notes      ::= "\"notes\"" ws ":" ws string
scope      ::= "\"scope\"" ws ":" ws ("\"today\"" | "\"all_open\"" | "\"afternoon\"" | "\"day\"")
day        ::= "\"day\"" ws ":" ws ("\"today\"" | "\"tomorrow\"" | "\"monday\"" | "\"tuesday\"" | "\"wednesday\""
                                 | "\"thursday\"" | "\"friday\"" | "\"saturday\"" | "\"sunday\"")
time       ::= "\"time\"" ws ":" ws "\"" ([01] [0-9] | "2" [0-3]) ":" [0-5] [0-9] "\""
clear      ::= "\"clear_deadline\"" ws ":" ws ("true" | "false")
duration   ::= "\"duration_min\"" ws ":" ws [1-9] [0-9]? [0-9]? [0-9]?
priority   ::= "\"priority\"" ws ":" ws ("\"high\"" | "\"medium\"" | "\"low\"")
recurrence ::= "\"recurrence\"" ws ":" ws ("\"none\"" | "\"daily\"" | "\"weekdays\"" | "\"weekly\"" | "\"monthly\"")

string ::= "\"" char{0,120} "\""
char   ::= [^"\\\x00-\x1f] | "\\" ["\\/bfnrt]
ws     ::= [ ]?
""".trimIndent()
}
