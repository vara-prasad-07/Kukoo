package com.example.kukoo.ai

/**
 * GBNF grammar (llama.cpp / qairt) for the flat JSON command object [LlamaIntentParser] asks for.
 *
 * The keys allowed are chosen **per action**, not shared across all of them. A grammar that let
 * any key follow any action produced things like
 * `{"action":"update_task","new_title":"Gym","clear_deadline":true,"scope":"today","kind":"help"}`
 * — an update with no target, a scope that belongs to a query and a chat kind — which the parser
 * then had to throw away. Encoding the shape here means the sampler cannot emit that at all, so
 * "update this task" always carries the task, and a chat reply always carries its kind.
 */
object LlamaGrammar {
    val COMMAND_JSON: String = """
root ::= "{" ws "\"action\"" ws ":" ws body ws "}"

body ::= addbody | answerbody | querybody | updatebody | targetbody | replanbody | snoozebody
       | chatbody | findbody | planbody | plantasksbody | planchangebody | simplebody

# A new task: whatever details the user gave, and never a target (it does not exist yet).
addbody    ::= "\"add_task\"" (ws "," ws detail)*

# The reply to the question the assistant just asked: the same details, nothing else.
answerbody ::= "\"answer\"" (ws "," ws detail)*

detail ::= title | day | time | duration | priority | recurrence | notes | reminder

# An edit of an existing task always names the task first.
updatebody ::= "\"update_task\"" ws "," ws target (ws "," ws upmember)*
upmember   ::= newtitle | day | time | clear | duration | priority | recurrence | notes | reminder

# Actions that do nothing but point at one task.
targetbody ::= ("\"complete_task\"" | "\"reopen_task\"" | "\"delete_task\"" | "\"ask_what_to_change\"")
               ws "," ws target

querybody  ::= "\"query_tasks\"" (ws "," ws "\"scope\"" ws ":" ws ("\"today\"" | "\"all_open\""))?
replanbody ::= "\"replan\"" (ws "," ws "\"scope\"" ws ":" ws ("\"afternoon\"" | "\"day\""))?
snoozebody ::= "\"snooze\"" (ws "," ws duration)?
findbody   ::= "\"find_time\"" (ws "," ws duration)?
chatbody   ::= "\"chat\"" ws "," ws chatkind
simplebody ::= "\"discard_task\"" | "\"undo\"" | "\"end_call\"" | "\"check_conflicts\"" | "\"unsupported\"" | "\"approve_plan\""

# Planning a day: a day or date, and the tasks the user listed, each as its own small object.
planbody       ::= "\"plan_day\"" (ws "," ws (day | date))? (ws "," ws tasks)?
plantasksbody  ::= "\"plan_tasks\"" ws "," ws tasks
planchangebody ::= "\"plan_change\"" (ws "," ws pchange)*
pchange        ::= target | day | date | time | duration | priority | remove
tasks          ::= "\"tasks\"" ws ":" ws "[" ws taskobj (ws "," ws taskobj)* ws "]"
taskobj        ::= "{" ws title (ws "," ws (duration | priority | time))* ws "}"

title      ::= "\"title\"" ws ":" ws string
target     ::= "\"target\"" ws ":" ws string
newtitle   ::= "\"new_title\"" ws ":" ws string
notes      ::= "\"notes\"" ws ":" ws string
day        ::= "\"day\"" ws ":" ws ("\"today\"" | "\"tomorrow\"" | "\"monday\"" | "\"tuesday\"" | "\"wednesday\""
                                 | "\"thursday\"" | "\"friday\"" | "\"saturday\"" | "\"sunday\"")
time       ::= "\"time\"" ws ":" ws "\"" ([01] [0-9] | "2" [0-3]) ":" [0-5] [0-9] "\""
clear      ::= "\"clear_deadline\"" ws ":" ws ("true" | "false")
remove     ::= "\"remove\"" ws ":" ws ("true" | "false")
date       ::= "\"date\"" ws ":" ws "\"" [0-9] [0-9] [0-9] [0-9] "-" ("0" [1-9] | "1" [0-2]) "-" ("0" [1-9] | [12] [0-9] | "3" [01]) "\""
duration   ::= "\"duration_min\"" ws ":" ws [1-9] [0-9]? [0-9]? [0-9]?
reminder   ::= "\"reminder_min\"" ws ":" ws ("0" | [1-9] [0-9]? [0-9]? [0-9]?)
priority   ::= "\"priority\"" ws ":" ws ("\"high\"" | "\"medium\"" | "\"low\"")
recurrence ::= "\"recurrence\"" ws ":" ws ("\"none\"" | "\"daily\"" | "\"weekdays\"" | "\"weekly\"" | "\"monthly\"")
chatkind   ::= "\"kind\"" ws ":" ws ("\"greeting\"" | "\"thanks\"" | "\"acknowledge\"" | "\"help\"")

string ::= "\"" char{0,120} "\""
char   ::= [^"\\\x00-\x1f] | "\\" ["\\/bfnrt]
ws     ::= [ ]?
""".trimIndent()
}
