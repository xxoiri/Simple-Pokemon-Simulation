package src.pas.pokemon.agents;

import edu.bu.pas.pokemon.core.Agent;
import edu.bu.pas.pokemon.core.Battle.BattleView;
import edu.bu.pas.pokemon.core.Team.TeamView;
import edu.bu.pas.pokemon.core.Move.MoveView;
import edu.bu.pas.pokemon.core.Pokemon.PokemonView;
import edu.bu.pas.pokemon.utils.Pair;
import edu.bu.pas.pokemon.core.enums.Stat;
import edu.bu.pas.pokemon.core.enums.Type;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;

public class TreeTraversalAgent extends Agent {
    private class Node {
        private final BattleView battleView;
        private final boolean isMaxNode;
        private final int depth;
        private final MoveView move;
        private final double probability;

        public Node(BattleView battleView, boolean isMaxNode, int depth, MoveView move, double probability) {
            this.battleView = battleView;
            this.isMaxNode = isMaxNode;
            this.depth = depth;
            this.move = move;
            this.probability = probability;
        }

        public BattleView getBattleView() { return this.battleView; }
        public boolean getIsMaxNode() { return this.isMaxNode; }
        public int getDepth() { return this.depth; }
        public MoveView getMove() { return this.move; }
        public double getProbability() { return this.probability; }
    }

    private class StochasticTreeSearcher implements Callable<Pair<MoveView, Long>> {
        private final BattleView rootView;
        private final int maxDepth;
        private final int myTeamIdx;

        public StochasticTreeSearcher(BattleView rootView, int maxDepth, int myTeamIdx) {
            this.rootView = rootView;
            this.maxDepth = maxDepth;
            this.myTeamIdx = myTeamIdx;
        }

        public BattleView getRootView() { return this.rootView; }
        public int getMaxDepth() { return this.maxDepth; }
        public int getMyTeamIdx() { return this.myTeamIdx; }

        private double evaluateUtility(BattleView battleView) {
            if (battleView == null) return 0;
            
            TeamView myTeam = battleView.getTeamView(myTeamIdx);
            TeamView opponentTeam = battleView.getTeamView(1 - myTeamIdx);
                        
            double utility = 0.0;
            PokemonView myActive = myTeam.getActivePokemonView();
            PokemonView oppActive = opponentTeam.getActivePokemonView();
            
            if (myActive == null || oppActive == null|| myActive == null || oppActive == null) return 0;
            
            // 1. HP difference (most important factor)
            double myHpRatio = (double)myActive.getCurrentStat(Stat.HP) / myActive.getInitialStat(Stat.HP);
            double oppHpRatio = (double)oppActive.getCurrentStat(Stat.HP) / oppActive.getInitialStat(Stat.HP);
            utility += (myHpRatio - oppHpRatio) * 300;
            
            // 2. Type advantage scoring
            Type myType = myActive.getCurrentType1();
            Type oppType = oppActive.getCurrentType1();
            
            if (Type.isSuperEffective(myType, oppType)) {
                utility += 100;
            }
            
            // 3. Status conditions
            if (oppActive.getNonVolatileStatus() != null) {
                utility += 50;
            }
            if (myActive.getNonVolatileStatus() != null) {
                utility -= 50;
            }
            
            // 4. Team alive count
            int myAlive = countAlivePokemon(myTeam);
            int oppAlive = countAlivePokemon(opponentTeam);
            utility += (myAlive - oppAlive) * 200;
            
            return utility;
        }

        private double evaluateMoveEffectiveness(MoveView move, PokemonView target) {
            if (move == null || target == null) return 0;

            if (move.getPower() == null) return evaluateStatusMove(move, target); // Skip status moves

            // Convert to primitive types
            int powerInt = move.getPower();
            int accuracy = move.getAccuracy();
            double critRate = move.getCriticalHitRatio();
            
            // Calculate the actual value to show
            double moveValue = calculateMoveValue(powerInt, accuracy, critRate, move.getType(), 
                                            target.getCurrentType1(), target.getCurrentType2());
            
            // Debug output - show REAL calculated value
            System.out.printf(
                "Evaluating %-10s → Value: %5.1f (Power: %3d, Acc: %3d, Crit: %.1f%%)%n",
                move.getName(),
                moveValue, 
                powerInt,
                accuracy,
                critRate * 100
            );

            return moveValue;
        }

        private double evaluateStatusMove(MoveView move, PokemonView target) {
            PokemonView opponent = rootView.getTeamView(1 - myTeamIdx).getActivePokemonView();


            // Give strategic value to common status effects
            switch (move.getName().toUpperCase()) {
                case "SWORDS DANCE":
                case "DRAGON DANCE":
                    return 80; // Boosts are valuable
                case "HYPNOSIS":
                case "SLEEP POWDER":
                    return 70; // Sleep is powerful
                case "REFLECT":
                case "LIGHT SCREEN":
                    return 60; // Defensive boosts
                case "SPLASH":
                    return 5; // Minimal value but not null
                default:
                    return 30; // Default value for other status moves
            }
        }

        private double calculateMoveValue(int power, int accuracy, double critRate, 
                                        Type moveType, Type targetType1, Type targetType2) {
            double powerScore = power / 100.0;
            double accuracyPenalty = (accuracy < 100) ? 0.7 : 1.0;
            double critBonus = 1.0 + (critRate * 0.5);

            double effectiveness = Type.getEffectivenessModifier(moveType, targetType1);
            if (targetType2 != null) {
                effectiveness *= Type.getEffectivenessModifier(moveType, targetType2);
            }
            
            return effectiveness * powerScore * critBonus * accuracyPenalty * 100;
        }


        private int countAlivePokemon(TeamView team) {
            if (team == null) return 0;
            int count = 0;
            for (int i = 0; i < team.size(); i++) {
                PokemonView pokemon = team.getPokemonView(i);
                if (pokemon != null && !pokemon.hasFainted()) {
                    count++;
                }
            }
            return count;
        }

        private List<MoveView> getAvailableMoves(BattleView battleView, int teamIdx) {
            List<MoveView> availableMoves = new ArrayList<>();
            if (battleView == null) return availableMoves;
            
            TeamView team = battleView.getTeamView(teamIdx);
            if (team == null) return availableMoves;
            
            PokemonView activePokemon = team.getActivePokemonView();
            if (activePokemon == null) return availableMoves;
            
            // Add regular moves
            for (MoveView move : activePokemon.getAvailableMoves()) {
                if (move != null) {
                    availableMoves.add(move);
                }
            }

            // Add switch moves (represented as null)
            int activeIndex = team.getActivePokemonIdx();
            for (int i = 0; i < team.size(); i++) {
                if (i != activeIndex) {
                    PokemonView pokemon = team.getPokemonView(i);
                    if (pokemon != null && !pokemon.hasFainted()) {
                        availableMoves.add(null);
                    }
                }
            }
            return availableMoves;
        }

        public MoveView stochasticTreeSearch(BattleView rootView) {
            PokemonView activePokemon = rootView.getTeamView(myTeamIdx).getActivePokemonView();
            printAvailableMoves(activePokemon);

            List<MoveView> possibleMoves = getAvailableMoves(rootView, myTeamIdx);
            if (possibleMoves == null || possibleMoves.isEmpty()) {
                return null;
            }
            
            // Remove null moves if they're not handled
            possibleMoves.removeIf(move -> move == null);
            
            if (possibleMoves.isEmpty()) {
                return null;
            }
            
            MoveView bestMove = possibleMoves.get(0);
            double bestValue = Double.NEGATIVE_INFINITY;
            
            for (MoveView move : possibleMoves) {
                try {
                    PokemonView target = rootView.getTeamView(1 - myTeamIdx).getActivePokemonView();
                    if (target == null) continue;
                    
                    double moveValue = evaluateMoveEffectiveness(move, target);
                    
                    if (moveValue > bestValue * 0.8) {
                        double futureValue = expectimax(new Node(
                            rootView,
                            false,
                            1,
                            move,
                            1.0
                        ), move);
                        moveValue = moveValue * 0.4 + futureValue * 0.6;
                    }
                    
                    if (moveValue > bestValue) {
                        bestValue = moveValue;
                        bestMove = move;
                    }
                } catch (Exception e) {
                    System.err.println("Error evaluating move: " + e.getMessage());
                    continue;
                }
            }
            
            return bestMove;
        }

        private double expectimax(Node node, MoveView moveToEvaluate) {
            if (node == null || node.getBattleView() == null) {
                return 0;
            }
            
            if (node.getDepth() >= maxDepth) {
                return evaluateUtility(node.getBattleView());
            }

            if (node.getIsMaxNode()) {
                double maxValue = Double.NEGATIVE_INFINITY;
                List<MoveView> possibleMoves = getAvailableMoves(node.getBattleView(), myTeamIdx);
                
                for (MoveView move : possibleMoves) {
                    if (move == null) continue;
                    double value = expectimax(new Node(
                        node.getBattleView(),
                        false,
                        node.getDepth() + 1,
                        move,
                        1.0
                    ), move);
                    maxValue = Math.max(maxValue, value);
                }
                return maxValue;
            } else {
                // Opponent's turn simulation
                return evaluateUtility(node.getBattleView());
            }
        }

        @Override
        public Pair<MoveView, Long> call() throws Exception {
            long startTime = System.nanoTime();
            MoveView move = this.stochasticTreeSearch(this.getRootView());
            long endTime = System.nanoTime();
            return new Pair<>(move, (endTime - startTime) / 1000000);
        }
    }

    private final int maxDepth;
    private final long maxThinkingTimePerMoveInMS;

    public TreeTraversalAgent() {
        super();
        this.maxThinkingTimePerMoveInMS = 180000 * 2; // 6 minutes per move
        this.maxDepth = 4;
    }

    public int getMaxDepth() { return this.maxDepth; }
    public long getMaxThinkingTimePerMoveInMS() { return this.maxThinkingTimePerMoveInMS; }

    @Override
    public Integer chooseNextPokemon(BattleView view) {
        if (view == null) return null;
        
        TeamView myTeam = view.getTeamView(this.getMyTeamIdx());
        if (myTeam == null) return null;
        
        // Simple implementation - choose first available non-fainted Pokemon
        for (int i = 0; i < myTeam.size(); i++) {
            PokemonView pokemon = myTeam.getPokemonView(i);
            if (pokemon != null && !pokemon.hasFainted()) {
                return i;
            }
        }
        return null;
    }

    private void printAvailableMoves(PokemonView pokemon) {
        if (pokemon == null) {
            System.out.println("No active Pokémon!");
            return;
        }
        
        System.out.println("\n" + pokemon.getName() + "'s Moves:");
        for (MoveView move : pokemon.getAvailableMoves()) {
            System.out.printf("- %-15s (Type: %-8s Power: %-3d Accuracy: %-3d)%n",
                move.getName(),
                move.getType(),
                move.getPower(),
                move.getAccuracy());
        }
    }

    @Override
    public MoveView getMove(BattleView battleView) {
        if (battleView == null) return null;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        MoveView move = null;
        
        try {
            StochasticTreeSearcher searcher = new StochasticTreeSearcher(
                battleView,
                this.getMaxDepth(),
                this.getMyTeamIdx()
            );

            Future<Pair<MoveView, Long>> future = executor.submit(searcher);
            Pair<MoveView, Long> result = future.get(
                this.getMaxThinkingTimePerMoveInMS(),
                TimeUnit.MILLISECONDS
            );
            move = result.getFirst();
        } catch (TimeoutException e) {
            System.err.println("Timeout! Using fallback move.");
            move = getFallbackMove(battleView);
        } catch (Exception e) {
            System.err.println("Error in getMove: " + e.getMessage());
            move = getFallbackMove(battleView);
        } finally {
            executor.shutdown();
        }
        
        return move;
    }

    private MoveView getFallbackMove(BattleView battleView) {
        if (battleView == null) return null;
        
        TeamView team = battleView.getTeamView(this.getMyTeamIdx());
        if (team == null) return null;
        
        PokemonView active = team.getActivePokemonView();
        if (active == null) return null;
        
        List<MoveView> moves = active.getAvailableMoves();
        return moves.isEmpty() ? null : moves.get(0);
    }
}