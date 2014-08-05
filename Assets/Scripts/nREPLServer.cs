using UnityEngine;
using System.Collections;

public class nREPLServer : ClojureDelegate {
	nREPLServer() {
        nameSpace = "nrepl";
        prefix = "";
    }
}
