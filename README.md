unity-nrepl
===========

Clojure nREPL for Unity 3D

Add an embedded nrepl server to your Unity 3D game. You can leave your game running in one window and develop it completely from emacs in another without ever switching back to Unity.

This project is depends and is based on:

* https://github.com/clojure-unity/clojure-unity
* https://github.com/clojure-unity/clojure-clr

The nREPL code is a hacked up version of:

* https://github.com/clojure/clr.tools.nrepl

Requires: cider-0.6alpha (currently ships with [emacs-live](https://github.com/overtone/emacs-live))

Note: Does work with cider-0.6.0 but when asks you for a lisp expression when connecting. Cider 0.7.0beta also works but asks for 3 lisp expressions when connecting. I usually just enter the number 1 or something. Cider-0.6alpha works fine.

Remaining issues:
 * nrepl editor disconnects when project changes or new files are add or removed. This only of course pertains to the development of editors.
 * sometimes Emacs crashes when typing... I think it is the autocomplete/suggest code.

Todo:
* fix nREPL Editor disable issues.
* support new versions of cider.
* build a set of middleware based on the cider-nrepl set of middleware.
* Replaced BlockingCollection with a List... perhaps do something about that.
