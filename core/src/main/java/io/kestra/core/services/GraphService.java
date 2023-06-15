package io.kestra.core.services;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.TaskRun;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.hierarchies.*;
import io.kestra.core.models.tasks.FlowableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.tasks.flows.Dag;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GraphService {
    public static GraphCluster of(Flow flow, Execution execution) throws IllegalVariableEvaluationException {
        GraphCluster graph = new GraphCluster();

        if (flow.getTriggers() != null) {
            GraphCluster triggers = GraphService.triggers(graph, flow.getTriggers());
            graph.getGraph().addEdge(triggers.getEnd(), graph.getRoot(), new Relation());
        }

        GraphService.sequential(
            graph,
            flow.getTasks(),
            flow.getErrors(),
            null,
            execution
        );

        return graph;
    }

    public static GraphCluster triggers(GraphCluster graph, List<AbstractTrigger> triggers) throws IllegalVariableEvaluationException {
        GraphCluster triggerCluster = new GraphCluster("Triggers");

        triggers.forEach(trigger -> {
            GraphTrigger triggerNode = new GraphTrigger(trigger);
            triggerCluster.getGraph().addNode(triggerNode);
            triggerCluster.getGraph().addEdge(triggerCluster.getRoot(), triggerNode, new Relation());
            triggerCluster.getGraph().addEdge(triggerNode, triggerCluster.getEnd(), new Relation());
        });

        graph.getGraph().addNode(triggerCluster);

        return triggerCluster;
    }

    public static List<AbstractGraph> nodes(GraphCluster graphCluster) {
        return graphCluster.getGraph().nodes()
            .stream()
            .flatMap(t -> t instanceof GraphCluster ? nodes((GraphCluster) t).stream() : Stream.of(t))
            .distinct()
            .collect(Collectors.toList());
    }

    private static List<Triple<AbstractGraph, AbstractGraph, Relation>> rawEdges(GraphCluster graphCluster) {
        return Stream.concat(
                graphCluster.getGraph().edges()
                    .stream()
                    .map(r -> Triple.of(r.getSource(), r.getTarget(), r.getValue())),
                graphCluster.getGraph().nodes()
                    .stream()
                    .flatMap(t -> t instanceof GraphCluster ? rawEdges((GraphCluster) t).stream() : Stream.of())
            )
            .collect(Collectors.toList());
    }

    public static List<FlowGraph.Edge> edges(GraphCluster graphCluster) {
        return rawEdges(graphCluster)
            .stream()
            .map(r -> new FlowGraph.Edge(r.getLeft().getUid(), r.getMiddle().getUid(), r.getRight()))
            .collect(Collectors.toList());
    }

    public static List<Pair<GraphCluster, List<String>>> clusters(GraphCluster graphCluster, List<String> parents) {
        return graphCluster.getGraph().nodes()
            .stream()
            .flatMap(t -> {

                if (t instanceof GraphCluster) {
                    ArrayList<String> currentParents = new ArrayList<>(parents);
                    currentParents.add(t.getUid());

                    return Stream.concat(
                        Stream.of(Pair.of((GraphCluster) t, parents)),
                        clusters((GraphCluster) t, currentParents).stream()
                    );
                }

                return Stream.of();
            })
            .collect(Collectors.toList());
    }

    public static List<String> flowables(Flow flow) {
        return flow.allTasksWithChilds()
            .stream()
            .filter(task -> task instanceof FlowableTask)
            .map(task -> task.getId())
            .collect(Collectors.toList());
    }

    public static Set<AbstractGraph> successors(GraphCluster graphCluster, List<String> taskRunIds) {
        List<FlowGraph.Edge> edges = GraphService.edges(graphCluster);
        List<AbstractGraph> nodes = GraphService.nodes(graphCluster);

        List<AbstractGraph> selectedTaskRuns = nodes
            .stream()
            .filter(task -> task instanceof AbstractGraphTask)
            .filter(task -> ((AbstractGraphTask) task).getTaskRun() != null && taskRunIds.contains(((AbstractGraphTask) task).getTaskRun().getId()))
            .collect(Collectors.toList());

        Set<String> edgeUuid = selectedTaskRuns
            .stream()
            .flatMap(task -> recursiveEdge(edges, task.getUid()).stream())
            .map(FlowGraph.Edge::getSource)
            .collect(Collectors.toSet());

        return nodes
            .stream()
            .filter(task -> edgeUuid.contains(task.getUid()))
            .collect(Collectors.toSet());
    }

    private static List<FlowGraph.Edge> recursiveEdge(List<FlowGraph.Edge> edges, String selectedUuid) {
        return edges
            .stream()
            .filter(edge -> edge.getSource().equals(selectedUuid))
            .flatMap(edge -> Stream.concat(
                Stream.of(edge),
                recursiveEdge(edges, edge.getTarget()).stream()
            ))
            .collect(Collectors.toList());
    }

    public static void sequential(
        GraphCluster graph,
        List<Task> tasks,
        List<Task> errors,
        TaskRun parent,
        Execution execution
    ) throws IllegalVariableEvaluationException {
        iterate(graph, tasks, errors, parent, execution, RelationType.SEQUENTIAL);
    }

    public static void parallel(
        GraphCluster graph,
        List<Task> tasks,
        List<Task> errors,
        TaskRun parent,
        Execution execution
    ) throws IllegalVariableEvaluationException {
        iterate(graph, tasks, errors, parent, execution, RelationType.PARALLEL);
    }

    public static void switchCase(
        GraphCluster graph,
        Map<String, List<Task>> tasks,
        List<Task> errors,
        TaskRun parent,
        Execution execution
    ) throws IllegalVariableEvaluationException {
        for (Map.Entry<String, List<Task>> entry: tasks.entrySet()) {
            fillGraph(graph, entry.getValue(), RelationType.SEQUENTIAL, parent, execution, entry.getKey());
        }

        // error cases
        if (errors != null && errors.size() > 0) {
            fillGraph(graph, errors, RelationType.ERROR, parent, execution, null);
        }
    }

    public static void ifElse(
        GraphCluster graph,
        List<Task> then,
        List<Task> _else,
        List<Task> errors,
        TaskRun parent,
        Execution execution
    ) throws IllegalVariableEvaluationException {
        fillGraph(graph, then, RelationType.SEQUENTIAL, parent, execution, "then");
        if (_else != null) {
            fillGraph(graph, _else, RelationType.SEQUENTIAL, parent, execution, "else");
        }

        // error cases
        if (errors != null && errors.size() > 0) {
            fillGraph(graph, errors, RelationType.ERROR, parent, execution, null);
        }
    }

    public static void dag(
        GraphCluster graph,
        List<Dag.DagTask> tasks,
        List<Task> errors,
        TaskRun parent,
        Execution execution
    ) throws IllegalVariableEvaluationException {
        fillGraphDag(graph, tasks, parent, execution);

        // error cases
        if (errors != null && errors.size() > 0) {
            fillGraph(graph, errors, RelationType.ERROR, parent, execution, null);
        }
    }

    private static void iterate(
        GraphCluster graph,
        List<Task> tasks,
        List<Task> errors,
        TaskRun parent,
        Execution execution,
        RelationType relationType
    ) throws IllegalVariableEvaluationException {
        // standard cases
        fillGraph(graph, tasks, relationType, parent, execution, null);

        // error cases
        if (errors != null && errors.size() > 0) {
            fillGraph(graph, errors, RelationType.ERROR, parent, execution, null);
        }
    }

    private static void fillGraph(
        GraphCluster graph,
        List<Task> tasks,
        RelationType relationType,
        TaskRun parent,
        Execution execution,
        String value
    ) throws IllegalVariableEvaluationException {
        Iterator<Task> iterator = tasks.iterator();
        AbstractGraph previous;

        // we validate a GraphTask based on 3 nodes (root/ task / end)
        if (graph.getGraph().nodes().size() >= 3 && new ArrayList<>(graph.getGraph().nodes()).get(2) instanceof GraphTask) {
            previous = new ArrayList<>(graph.getGraph().nodes()).get(2);
        } else {
            previous = graph.getRoot();
        }

        boolean isFirst = true;
        while (iterator.hasNext()) {
            Task currentTask = iterator.next();
            for (TaskRun currentTaskRun : findTaskRuns(currentTask, execution, parent)) {
                AbstractGraph currentGraph;
                List<String> parentValues = null;

                // we use the graph relation type by default but we change it to pass relation for case below
                RelationType newRelation = graph.getRelationType();
                if (relationType == RelationType.ERROR) {
                    newRelation = relationType;
                } else if ((!isFirst && relationType != RelationType.PARALLEL && graph.getRelationType() != RelationType.DYNAMIC)) {
                    newRelation = relationType;
                }

                Relation relation = new Relation(
                    newRelation,
                    currentTaskRun == null ? value : currentTaskRun.getValue()
                );

                if (execution != null && currentTaskRun != null) {
                    parentValues = execution.findChildsValues(currentTaskRun, true);
                }

                // detect kids
                if (currentTask instanceof FlowableTask) {
                    FlowableTask<?> flowableTask = ((FlowableTask<?>) currentTask);
                    currentGraph = flowableTask.tasksTree(execution, currentTaskRun, parentValues);
                } else {
                    currentGraph = new GraphTask(currentTask, currentTaskRun, parentValues, relationType);
                }

                // add the node
                graph.getGraph().addNode(currentGraph);

                // link to previous one
                if (previous != null) {
                    if (previous instanceof GraphCluster && ((GraphCluster) previous).getEnd() != null) {
                        graph.getGraph().addEdge(
                            ((GraphCluster) previous).getEnd(),
                            currentGraph instanceof GraphCluster ? ((GraphCluster) currentGraph).getRoot() : currentGraph,
                            relation
                        );
                    } else {
                        graph.getGraph().addEdge(
                            previous,
                            currentGraph instanceof GraphCluster ? ((GraphCluster) currentGraph).getRoot() : currentGraph,
                            relation
                        );
                    }
                }

                // change previous for current one to link
                if (relationType != RelationType.PARALLEL) {
                    previous = currentGraph;
                }

                // link to end task
                if (GraphService.isAllLinkToEnd(relationType)) {
                    if (currentGraph instanceof GraphCluster && ((GraphCluster) currentGraph).getEnd() != null) {
                        graph.getGraph().addEdge(
                            ((GraphCluster) currentGraph).getEnd(),
                            graph.getEnd(),
                            new Relation()
                        );
                    } else {
                        graph.getGraph().addEdge(
                            currentGraph,
                            graph.getEnd(),
                            new Relation()
                        );
                    }
                }

                isFirst = false;

                if (!iterator.hasNext() && !isAllLinkToEnd(relationType)) {
                    graph.getGraph().addEdge(
                        currentGraph instanceof GraphCluster ? ((GraphCluster) currentGraph).getEnd() : currentGraph,
                        graph.getEnd(),
                        new Relation()
                    );
                }
            }
        }
    }


    private static void fillGraphDag(
        GraphCluster graph,
        List<Dag.DagTask> tasks,
        TaskRun parent,
        Execution execution
    ) throws IllegalVariableEvaluationException {
        List<GraphTask> nodeTaskCreated = new ArrayList<>();
        List<String> nodeCreatedIds = new ArrayList<>();
        Set<String> dependencies = tasks.stream().filter(taskDepend -> taskDepend.getDependsOn() != null).map(Dag.DagTask::getDependsOn).flatMap(Collection::stream).collect(Collectors.toSet());
        AbstractGraph previous;

        // we validate a GraphTask based on 3 nodes (root/ task / end)
        if (graph.getGraph().nodes().size() >= 3 && new ArrayList<>(graph.getGraph().nodes()).get(2) instanceof GraphTask) {
            previous = new ArrayList<>(graph.getGraph().nodes()).get(2);
        } else {
            previous = graph.getRoot();
        }

        while(nodeCreatedIds.size() < tasks.size()) {
            Iterator<Dag.DagTask> iterator = tasks.stream().filter(taskDepend ->
                // Check if the task has no dependencies OR all if its dependencies have been treated
                (taskDepend.getDependsOn() == null || new HashSet<>(nodeCreatedIds).containsAll(taskDepend.getDependsOn()))
                // AND if the task has not been treated yet
                && !nodeCreatedIds.contains(taskDepend.getTask().getId())).iterator();
            while (iterator.hasNext()) {
                Dag.DagTask currentTask = iterator.next();
                for (TaskRun currentTaskRun : findTaskRuns(currentTask.getTask(), execution, parent)) {
                    AbstractGraph currentGraph;
                    List<String> parentValues = null;

                    RelationType newRelation = RelationType.PARALLEL;

                    Relation relation = new Relation(
                        newRelation,
                        currentTaskRun == null ? null : currentTaskRun.getValue()
                    );

                    if (execution != null && currentTaskRun != null) {
                        parentValues = execution.findChildsValues(currentTaskRun, true);
                    }

                    // detect kids
                    if (currentTask.getTask() instanceof FlowableTask<?> flowableTask) {
                        currentGraph = flowableTask.tasksTree(execution, currentTaskRun, parentValues);
                    } else {
                        currentGraph = new GraphTask(currentTask.getTask(), currentTaskRun, parentValues, RelationType.SEQUENTIAL);
                    }

                    // add the node
                    graph.getGraph().addNode(currentGraph);

                    // link to previous one
                    if(currentTask.getDependsOn() == null) {
                        graph.getGraph().addEdge(
                            previous,
                            currentGraph instanceof GraphCluster ? ((GraphCluster) currentGraph).getRoot() : currentGraph,
                            relation
                        );
                    } else {
                        for (String dependsOn : currentTask.getDependsOn()) {
                            GraphTask previousNode = nodeTaskCreated.stream().filter(node -> node.getTask().getId().equals(dependsOn)).findFirst().orElse(null);
                            if(previousNode!= null && !previousNode.getTask().isFlowable()) {
                                graph.getGraph().addEdge(
                                    previousNode,
                                    currentGraph instanceof GraphCluster ? ((GraphCluster) currentGraph).getRoot() : currentGraph,
                                    relation
                                );
                            } else {
                                graph.getGraph()
                                    .nodes()
                                    .stream()
                                    .filter(node -> node.getUid().equals("cluster_" + dependsOn))
                                    .findFirst()
                                    .ifPresent(previousClusterNodeEnd -> graph.getGraph().addEdge(
                                        ((GraphCluster) previousClusterNodeEnd).getEnd(),
                                        currentGraph instanceof GraphCluster ? ((GraphCluster) currentGraph).getRoot() : currentGraph,
                                        relation
                                ));
                            }
                        }
                    }
                    // link to last one if task isn't a dependency
                    if (!dependencies.contains(currentTask.getTask().getId())) {
                        if(currentTask.getTask() instanceof FlowableTask<?> flowableTask) {
                            graph.getGraph().addEdge(
                                ((GraphCluster) currentGraph).getEnd(),
                                graph.getEnd(),
                                new Relation()
                            );
                        } else {
                            graph.getGraph().addEdge(
                                currentGraph,
                                graph.getEnd(),
                                new Relation()
                            );
                        }
                    }

                    if(currentGraph instanceof GraphTask) {
                        nodeTaskCreated.add((GraphTask) currentGraph);
                    }
                    nodeCreatedIds.add(currentTask.getTask().getId());
                }
            }
        }
    }


    private static void generatePath(Dag.DagTask task, Map<String, Dag.DagTask> taskMap, List<Dag.DagTask> currentPath,
                                     List<List<Task>> allPaths, Set<String> visited) {
        currentPath.add(task);
        visited.add(task.getTask().getId());

        if (taskMap.containsKey(task.getTask().getId())) {
            List<String> dependsOnIds = task.getDependsOn();

            if(dependsOnIds != null) {
                for (String dependencyId : dependsOnIds) {
                    Dag.DagTask dependencyTask = taskMap.get(dependencyId);
                    if (!visited.contains(dependencyId)) {
                        generatePath(dependencyTask, taskMap, currentPath, allPaths, visited);
                    }
                }
            }
        }

        if (task.getDependsOn() == null) {
            Collections.reverse(currentPath);
            allPaths.add(currentPath.stream().map(Dag.DagTask::getTask).collect(Collectors.toList()));
        }

        currentPath.remove(currentPath.size() - 1);
        visited.remove(task.getTask().getId());
    }


    private static boolean isAllLinkToEnd(RelationType relationType) {
        return relationType == RelationType.PARALLEL || relationType == RelationType.CHOICE;
    }

    private static List<TaskRun> findTaskRuns(Task task, Execution execution, TaskRun parent) {
        List<TaskRun> taskRuns = execution != null ? execution.findTaskRunsByTaskId(task.getId()) : new ArrayList<>();

        if (taskRuns.size() == 0) {
            return Collections.singletonList(null);
        }

        return taskRuns
            .stream()
            .filter(taskRun -> parent == null  || (taskRun.getParentTaskRunId().equals(parent.getId())))
            .collect(Collectors.toList());
    }
}
