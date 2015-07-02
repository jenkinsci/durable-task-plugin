package org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy

def f = namespace(lib.FormTagLib);

f.entry(title: "Idle timeout", field: "idleMinutes") {
    f.number(default: 0)
}
