package synthesis;

import ic.doc.ltsa.common.iface.LTSOutput;
import java.util.*;



public class Synthesiser {

	static final String SYSNAME = "Synthesiser (with state labels)";
	static final String SYSVERSION = "v1.3";

	
	static final String INIT = "Init";
	static final String FINAL = "Final";
	static final String TAU = "_tau";

	static final String ENDPREFIX = "E_";
	static final String BEGINPREFIX = "B_";
	static final String CONDPREFIX = "C_";
	boolean LateSemantics;

	public Synthesiser(boolean LS)  {
		LateSemantics = LS;
	}

	/**
	Synthesises an FSP specification (<code>FSPOut</code>) from a MSC specification (<code>In</code>)
	delivering error and status messages through (<code>ErrorOut</code>) .
	
	@param In	An MSC specification to be used for synthesis.
	@param Out The synthesised FSP specification.
	
	@exception InconsistentSpecification 
	*/
	public void synthesiseFSP(Specification S, StringBuffer FSPOut, LTSOutput ErrorOut)throws Exception {
		MyOutput Output = new MyOutput(FSPOut);
//		S.removeConditions();
		long start = System.currentTimeMillis();
		Output.println("//Automatically generated by " + SYSNAME + " " + SYSVERSION);
		
		if (S != null) {
			getFSPSpecification(S, Output);
			long synthesisTime = (System.currentTimeMillis() - start);
			
			ErrorOut.outln("Total synthesis time: " + (synthesisTime) + " milliseconds.");
		}
	}
	
	
	//---------------------------------------------------------------------
	

	public void getFSPSpecification(Specification S, MyOutput Output) throws Exception {
		Set P; 
		String name, parallelComposition = "||System = (";
		boolean first = true;
		StringRelation CR, R; 
		Iterator Components = S.components().iterator();
		Map I; // String -> Instances: bMSC name and corresponding component instance in bMSC.
		CR = getCommonIsContinuationOfRelation(S);	
		try {
			while (Components.hasNext()) {
				name = (String) Components.next();
				if (!first) parallelComposition = parallelComposition + " || ";
				else first = false;
				parallelComposition = parallelComposition + name;
				I = S.getComponentInstances(name);
				R = getIsContinuationOfRelation(CR, I);
				//Set Q = getProductions(I);
				//Production.printProductions(name, Q, Output)	;		
				P = getAltProductions(getFSPProductions(getProductions(I), R)); 
				//R.print(Output);
				cleanUp(name, P);
				outputFSP(name,  P, Output);
			}	
			parallelComposition = parallelComposition + ").";
			Output.println(parallelComposition);
						
		} catch (Exception e) {
			throw e;
		}
	}
	
	
	public void printhMSCs(MyOutput Output, Specification S) throws Exception  {
		String line;
		Iterator J;
		boolean first;
		
		line = "deterministic DetHMSC = (";
		J = S.getContinuationsInit().iterator();
		first = true;
		while (J.hasNext()) {
			BasicMSC B = (BasicMSC) J.next();
			if (first) first = false; else line = line + " | ";
			line = line + "init -> " + B.name;
		}
		line = line + "),";
		Output.println(line);
		
		
		J = S.getbMSCs().iterator();
		first = true;
		while (J.hasNext()) {
			if (first) first = false; 
			else {line = line + ","; Output.println(line);}
		
			BasicMSC B = (BasicMSC) J.next();
			Iterator K = S.getContinuations(B).iterator();
			if (K.hasNext())  {
				line = B.name + " = (";
				boolean Kfirst = true;
				while (K.hasNext())  {
					BasicMSC KB = (BasicMSC) K.next();
					if (Kfirst) Kfirst = false; else line = line + " | ";
					line = line + "_" + B.name + " -> " + KB.name;
				}
				line = line + ")";
			}
			else
				line = B.name + " = STOP";
		}
		line = line + ".";
		Output.println(line);
		Output.println("property ||HSMC = DetHMSC\\{init}.");
		
		
		String parallelComposition = "deterministic ||ComposedHMSC = (";
		first = true;
		J = S.components().iterator();
		while (J.hasNext()) {
			String name = (String) J.next();
			line = "deterministic ||Det" + name + " = DetHMSC@{";
			if (first) first = false; else parallelComposition = parallelComposition + " || ";
			parallelComposition = parallelComposition + "Det" + name;
			Map M = S.getComponentInstances(name);
			Iterator K = M.keySet().iterator();
			boolean Kfirst = true;
			while (K.hasNext())  {
				String bMSCName = (String) K.next();
				Instance I = (Instance) M.get(bMSCName);
				if (I.size() > 0)  {
					if (Kfirst) Kfirst = false; else line = line + ", ";
					line = line + "_" + bMSCName;
				} 
			}
			line = line + "}.";
			Output.println(line);
		}
		
		parallelComposition = parallelComposition+ ").";
		Output.println(parallelComposition);
		Output.println("||Check = (HSMC || ComposedHMSC).");
	}
	
	
	/**
	Splits instances on condition. Returns a set of productions.
	While doing so it also normalises instances adding tau transitions between consecutive conditions
	and b_ScenarioName (e_scenarioname) conditions if the instance doesn't start (end) with a condition.
	
	@returns A set of productions
	@param Instances Mapping from a component name to its instances.
	*/
	
	private Set getProductions (Map Instances) {  
		HashSet retVal = new HashSet(); 
		Iterator Scenarios = Instances.keySet().iterator(); 
		
		Event e; 
		boolean foundMessageEvents;

		Production P = new Production();
		
		while (Scenarios.hasNext()) {
			String ScenarioName = (String) Scenarios.next();
			
			//Analyse first event
			ListIterator Events = ((Instance) Instances.get(ScenarioName)).iterator();
			if (Events.hasNext()) {
				e = (Event)Events.next();
				if (e instanceof ConditionEvent) {
					if (e.getLabel().equals(INIT))
						P.add(e.getLabel());
					else
						P.add(CONDPREFIX  + e.getLabel());
				}
				else {  //if no initial condition, normalise.
					P.add(BEGINPREFIX + ScenarioName);
					Events = ((Instance) Instances.get(ScenarioName)).iterator();
				}
			}
			
			//Analyse the rest...
			foundMessageEvents = false;
			while (Events.hasNext()) {
				e = (Event)Events.next();
				if (e instanceof ConditionEvent) {
					if (!foundMessageEvents) //if consecutive conditions, normalise.
						P.add(TAU);
					String SAux;
					if (e.getLabel().equals(INIT))
						SAux = e.getLabel();
					else
						SAux = CONDPREFIX  + e.getLabel();
					P.add(SAux);
					retVal.add(P);
					P = new Production();
					P.add(SAux);
					foundMessageEvents = false;
				}
				else {
					P.add(e.getLabel());
					foundMessageEvents = true;
				}
			}
			
			
			if (foundMessageEvents)  {  //if no final condition, normalise.
				P.add(ENDPREFIX + ScenarioName);
				retVal.add(P);
			}
			P = new Production();
		}
		return retVal;
	}

	/**
	Given a set of productions it produces a new set according to the IsAContinuationOf relation
	*/
	private Set getFSPProductions(Set Productions, StringRelation IsAContinuationOf) { 
				
		HashSet retVal = new HashSet(); // Set(Productions) 
		HashSet Intermediate = new HashSet();
		StringIterator FirstCont = null; 
		StringIterator LastCont;  //Iterator(Set(label)): The labels that CanBeSimulatedBy p.last
		String Aux; //Auxiliary: for finding the actual string that is in the mapping CanBeSimulatedBy
		 	
		
		
		//Replace A->......B with C->.....B for all (A,C) in IsContinuationOf
		
		Iterator I = Productions.iterator();
		while (I.hasNext()) {
			Production p = (Production) I.next();
			
			//Get IsAContinuationOf states. 
			StringSet Temp = IsAContinuationOf.getImage(p.first());
			if (Temp.isEmpty()) {
				Temp = new StringSet();
				Temp.add(p.first());
				FirstCont = Temp.stringIterator();
			}
			else {
				FirstCont = Temp.stringIterator(); 	
			}

			//Process states.
			while (FirstCont.hasNext()) {
				String s = FirstCont.nextString();
				Production PAux = (Production) p.clone();
				PAux.set(0, s);
				Intermediate.add(PAux);
			}
		}
		
		
	/*	//Replace C->......B with C->.....D for all (D,B) in IsContinuationOf
		Iterator K = Intermediate.iterator();
		while (K.hasNext()) {
			Production p = (Production) K.next();
			
			StringIterator J = IsAContinuationOf.domain().stringIterator();
			if (!J.hasNext())
				retVal.add(p);
			else  {	
				boolean found = false;
				while (J.hasNext()) { 
					//Get IsAContinuationOf states. 
					String s = J.nextString();
					if(IsAContinuationOf.getImage(s).contains(p.last())) {
						found = true;
						Production PAux = (Production) p.clone();
						PAux.set(PAux.size()-1, s);
						retVal.add(PAux);
					}
				}
				if (!found)
					retVal.add(p);
			}
		}
		
		return retVal;*/
		return Intermediate;
	}

	/*	Temp = IsAContinuationOf.getImage(p.last());//
		if (Temp.isEmpty()) {//
		//if (p.last().charAt(0) == CONDPREFIX.charAt(0)) {
			Temp = new StringSet();
			Temp.add(p.last());
			LastCont = Temp.stringIterator(); 
		//}
		}
		else {
			LastCont = Temp.stringIterator(); 
		}
		while (LastCont.hasNext()) {*/

			//		PAux.set(PAux.size()-1, LastCont.nextString());
	
				//	}
				
	/*
	Given a set of productions it merges productions with same left-hand side to produce a new set 
	productions with the alternative operator (altproductions)
	@param Q A set of productions.
	*/
	private Set getAltProductions(Set Q) { 
		Set retVal = new HashSet(); 
		AltProduction AP = new AltProduction("");
		
		Iterator Productions = Q.iterator(); 
		
		while (Productions.hasNext()) {	
				Production P = (Production) Productions.next();
				Iterator AltProductions = retVal.iterator(); 
				boolean found = false;
				
				while (AltProductions.hasNext()) {
					AP = (AltProduction) AltProductions.next();
					if (P.first().equals(AP.first)) {
						found = true;
						break;
					}
				}
				if (!found) { //Add AltProduction
					AP = new AltProduction(P.first());
					AP.addAlternative(P);
					retVal.add(AP);
				}
				else {
					found = false;
					Iterator Alternatives = AP.getAlternatives().iterator();
					while (Alternatives.hasNext()) { //Search for equivalent alternative
						Production A = (Production) Alternatives.next();
						if (P.equals(A)) {
							found = true;
							break;
						}
					}		
					if (!found) 
						AP.addAlternative(P); //Add alternative
				}
		}
		return retVal;
	}
				
	
	/**
	Cleans up a set of altproductions using standard grammar rules.
		
	*/
	
	private void cleanUp(String ComponentName, Set AP) {
		Grammar G = new Grammar (AP, INIT);
		boolean changed = true;
		boolean aux;
		String retVal="";
		int Cycles = 0;

		while (changed) {	
			Cycles++;
			changed = G.removeUnreachableNonTerminals(); 

			aux = G.removeTrivialProductions(); 
			changed = changed || aux;
						
			aux = G.removeRecursiveAlternatives();
			changed = changed || aux;

			aux = G.replaceTrivialAlternatives();
			changed = changed || aux;
			
			aux = G.removeDuplicateAlternatives();
			changed = changed || aux;

			aux = G.removeEquivalentProductions();
			changed = changed || aux;
		}
	}
	
	
	
	/** 
	Outputs a component in FSP format 
	
	@param Q is a Set(AltProduction)
	*/
	private void outputFSP(String ComponentName, Set Q, MyOutput Output) {

		boolean first;
		boolean hasFinal = false;
		boolean hasTau= false;
		
		int TOTALTABS = 6;
		String TABS = "\t\t\t\t\t\t";
		int TABLENGTH = 4;
		
		//Process header
		if (LateSemantics)
			Output.print("deterministic ");			
		Output.print("minimal " + ComponentName);		
		//for (int a = 1; a < TOTALTABS - (ComponentName.length() + 6) / TABLENGTH;a++) 
		//		Output.print("\t");
		Output.print(" = " + INIT);	
		
		//Process local processes.
		Iterator I = Q.iterator(); 
		while (I.hasNext()) {
			AltProduction AP = (AltProduction) I.next();
			
			Output.println( ",");
			Output.print(AP.first);
			
			for (int a = 1; a < TOTALTABS - AP.first.length() /TABLENGTH;a++) 
				Output.print("\t");
				
			
			//Process alternatives
			Iterator A = AP.getAlternatives().iterator();
			if (A.hasNext())  {
				Output.print(" = (");
				first = true;
				while (A.hasNext()) {
					if (first)
						first = false;
					else {
						Output.println( " | ");
						Output.print(TABS);
					}
					Production p = (Production) A.next();
					for (int a = 1; a<p.size(); a++) {
						String toprint = p.get(a);
						if (toprint.equals(TAU))
							hasTau=true;
						Output.print(toprint);
						if (a < p.size()-1)
							Output.print(" -> ");
					}
					hasFinal = hasFinal || (p.last().equals(FINAL));
				}
				Output.print(")");
			}
			else
				Output.print("=STOP");
		}
		
		//Process footer.
		if (hasFinal) {
			Output.println( ",");
			Output.print(FINAL);
			for (int a = 1; a < TOTALTABS - FINAL.length() / TABLENGTH;a++) 
					Output.print("\t");		
			Output.println( " = (" + TAU + "->STOP)\\{" + TAU + "}.");
		}
		else
			if (hasTau)
				Output.println( "\\{"+TAU+"}.");
			else
				Output.println( ".");

		Output.println( "");
	}

	
		
	//------------------------------------------------------------------------------------
	//Continuation Relation
	//------------------------------------------------------------------------------------
	
	private StringRelation getIsContinuationOfRelation(StringRelation M, Map Instances) {
		Iterator Scenarios = Instances.keySet().iterator();	
		Event e;
		StringRelation R = (StringRelation) M.clone();
		
				
		while (Scenarios.hasNext()) {
			String ScenarioName = (String) Scenarios.next();
			Instance I = (Instance) Instances.get(ScenarioName);
			if (I.size() > 0) {
				e = (Event)I.get(0);
				if (e instanceof ConditionEvent) {
					if (e.getLabel().equals(INIT))  {
						R.add(e.getLabel(), BEGINPREFIX + ScenarioName);
						R.add(BEGINPREFIX + ScenarioName, e.getLabel());
					}
					else	 {
						R.add(CONDPREFIX + e.getLabel(), BEGINPREFIX + ScenarioName);
						R.add(BEGINPREFIX + ScenarioName, CONDPREFIX + e.getLabel());
					}
				}
			
				e = (Event)I.get(I.size()-1);
				if (e instanceof ConditionEvent) {
					if (e.getLabel().equals(INIT))  {
						R.add(ENDPREFIX + ScenarioName, e.getLabel());
						R.add(e.getLabel(), ENDPREFIX + ScenarioName);
					}
					else  {
						R.add(ENDPREFIX + ScenarioName, CONDPREFIX + e.getLabel());
						R.add(CONDPREFIX + e.getLabel(), ENDPREFIX + ScenarioName);
					}
				}
			}
			else 
					R.add(ENDPREFIX + ScenarioName, BEGINPREFIX + ScenarioName);
		}
		R.transitiveClosure();		
			
		return R;
	
	}
	
	
	//------------------------------------------------------------------------------------
	//Common Continuation Relation
	//------------------------------------------------------------------------------------
	
	
	private StringRelation getCommonIsContinuationOfRelation(Specification S) {
			Map bMSCMap; // Map(BasicMSC x set(BasicMSC))
			StringRelation labelMap; 
			
			bMSCMap = buildBMSCRelation(S); 		
			labelMap = buildRelation(bMSCMap, S);  
			return labelMap;
	}

	//Builds a mapping that show the continuation relations between bMSCs
	private Map buildBMSCRelation(Specification S) {
		HashMap retVal = new HashMap(); 

		Iterator bMSCs = S.getbMSCs().iterator();
		while(bMSCs.hasNext()) {
			BasicMSC b = (BasicMSC) bMSCs.next();
			if (!retVal.containsKey(b)) 
				retVal.put(b, new HashSet());
			
			Iterator Continuations = S.getContinuations(b).iterator();
			
			while(Continuations.hasNext()) {
				BasicMSC c = (BasicMSC) Continuations.next();
				if (!retVal.containsKey(c)) 
					retVal.put(c, new HashSet());
				((Set)retVal.get(c)).add(b);
			}
		}		
		return retVal;
	}
	/**
	Converts bMSC relation into a string relation adding Init and Final states.
	
	@param M is a Map(BasicMSC x set(BasicMSC))
	*/
	private StringRelation buildRelation(Map M, Specification S) { 
		StringRelation retVal = new StringRelation(); 
		
		Iterator Keys = M.keySet().iterator();
		while(Keys.hasNext()) {
				BasicMSC b= (BasicMSC) Keys.next();
				String s = BEGINPREFIX + b.name;
				String t = ENDPREFIX + b.name;
				
				retVal.add(s, s);
				retVal.add(t, t);

				Iterator mapsto = ((Set) M.get(b)).iterator();
				while(mapsto.hasNext()) {
					BasicMSC c = (BasicMSC) mapsto.next();
					retVal.add(s,  ENDPREFIX + c.name);
				}
				if (S.getContinuationsInit().contains(b)) {
					retVal.add(s, INIT);					
				}
		}
		addFinal(retVal, S); 
		retVal.add(INIT, INIT);
		return retVal;
	}

	private void addFinal(StringRelation R, Specification Spec) {  
		Iterator I; 
			
		I = Spec.getContinuationsFinal().iterator();
		while (I.hasNext()) {
			BasicMSC b = (BasicMSC) I.next();
			R.add(FINAL, ENDPREFIX + b.name);
		}
	}
}
