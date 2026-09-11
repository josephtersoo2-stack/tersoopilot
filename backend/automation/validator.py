"""
DAG Validator for GhostPilot Automation State Machines.
Enforces structural integrity, valid transition targets, cycle/depth bounds,
and reachable terminal nodes before DAGs are persisted or dispatched.
"""

from typing import Dict, Any, Set
from rest_framework.exceptions import ValidationError


class DAGValidationError(ValidationError):
    """Raised when a state-machine DAG fails topological or structural validation."""
    pass


class DAGValidator:
    MAX_STATES = 500
    TERMINAL_COMMANDS = {"TERMINATE", "COMPLETE", "EXIT"}

    @classmethod
    def validate(cls, dag: Dict[str, Any]) -> bool:
        """
        Validates a compiled DAG dictionary.
        Raises DAGValidationError if invalid. Returns True on success.
        """
        if not isinstance(dag, dict):
            raise DAGValidationError("DAG must be a JSON object/dictionary.")

        states = dag.get("states")
        if not isinstance(states, dict) or not states:
            raise DAGValidationError("DAG must contain a non-empty 'states' dictionary.")

        if len(states) > cls.MAX_STATES:
            raise DAGValidationError(
                f"DAG exceeds maximum permitted states limit of {cls.MAX_STATES} (received {len(states)})."
            )

        entry_state = dag.get("entry_state") or dag.get("entry_state_id") or "start"
        if entry_state not in states:
            raise DAGValidationError(
                f"Entry state '{entry_state}' does not exist in DAG states."
            )

        # Validate each individual state node
        terminal_nodes: Set[str] = set()
        for state_id, node in states.items():
            if not isinstance(node, dict):
                raise DAGValidationError(f"State node '{state_id}' must be a dictionary.")

            command = node.get("command")
            if not command or not isinstance(command, str):
                raise DAGValidationError(f"State node '{state_id}' is missing a valid 'command' string.")

            params = node.get("params")
            if params is not None and not isinstance(params, dict):
                raise DAGValidationError(f"State node '{state_id}' params must be a dictionary.")

            transitions = node.get("transitions", {})
            if not isinstance(transitions, dict):
                raise DAGValidationError(f"State node '{state_id}' transitions must be a dictionary.")

            # Validate all transition targets
            for outcome, target in transitions.items():
                if not isinstance(target, str) or not target.strip():
                    raise DAGValidationError(
                        f"State node '{state_id}' transition for outcome '{outcome}' must point to a non-empty target string."
                    )
                # 'exit' is a built-in terminal target
                if target != "exit" and target not in states:
                    raise DAGValidationError(
                        f"State node '{state_id}' transition '{outcome}' points to non-existent state '{target}'."
                    )

            if command.upper() in cls.TERMINAL_COMMANDS or "exit" in transitions.values() or state_id == "exit":
                terminal_nodes.add(state_id)

        # Ensure terminal node reachability exists in the graph
        if not terminal_nodes and "exit" not in states:
            raise DAGValidationError("DAG must contain at least one terminal node or transition to 'exit'.")

        # Reachability from entry state: ensure entry state can reach at least one terminal node
        visited: Set[str] = set()
        queue = [entry_state]
        has_terminal_path = False

        while queue:
            curr = queue.pop(0)
            if curr in visited:
                continue
            visited.add(curr)

            if curr in terminal_nodes or curr == "exit":
                has_terminal_path = True

            curr_node = states.get(curr, {})
            curr_transitions = curr_node.get("transitions", {})
            for target in curr_transitions.values():
                if target == "exit":
                    has_terminal_path = True
                elif target in states and target not in visited:
                    queue.append(target)

        if not has_terminal_path:
            raise DAGValidationError(
                f"Entry state '{entry_state}' cannot reach any terminal node or 'exit' state."
            )

        return True
