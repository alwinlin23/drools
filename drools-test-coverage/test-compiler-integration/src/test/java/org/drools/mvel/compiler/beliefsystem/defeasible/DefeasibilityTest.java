/*
* Copyright 2012 Red Hat
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.drools.mvel.compiler.beliefsystem.defeasible;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.drools.core.BeliefSystemType;
import org.drools.core.ClassObjectFilter;
import org.drools.core.SessionConfiguration;
import org.drools.core.common.EqualityKey;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.common.TruthMaintenanceSystem;
import org.drools.core.common.TruthMaintenanceSystemFactory;
import org.drools.core.impl.RuleBaseFactory;
import org.drools.core.util.Iterator;
import org.drools.core.util.ObjectHashMap;
import org.drools.kiesession.session.StatefulKnowledgeSessionImpl;
import org.drools.testcoverage.common.util.KieBaseTestConfiguration;
import org.drools.testcoverage.common.util.KieBaseUtil;
import org.drools.testcoverage.common.util.TestParametersUtil;
import org.drools.tms.TruthMaintenanceSystemEqualityKey;
import org.drools.tms.beliefsystem.BeliefSet;
import org.drools.tms.beliefsystem.defeasible.DefeasibilityStatus;
import org.drools.tms.beliefsystem.defeasible.DefeasibleBeliefSet;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.kie.api.KieBase;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.KieSessionConfiguration;
import org.kie.api.runtime.rule.FactHandle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class DefeasibilityTest {

    private final KieBaseTestConfiguration kieBaseTestConfiguration;

    public DefeasibilityTest(final KieBaseTestConfiguration kieBaseTestConfiguration) {
        this.kieBaseTestConfiguration = kieBaseTestConfiguration;
    }

    @Parameterized.Parameters(name = "KieBase type={0}")
    public static Collection<Object[]> getParameters() {
     // TODO: EM failed with some tests. File JIRAs (maybe unsupported)
        return TestParametersUtil.getKieBaseCloudConfigurations(false);
    }

    protected KieSession getSessionFromString( String drlString) {
        KieBase kBase;

        try {
            System.setProperty("drools.negatable", "on");
            KieBaseTestConfiguration equalityConfig = TestParametersUtil.getEqualityInstanceOf(kieBaseTestConfiguration);
            kBase = KieBaseUtil.getKieBaseFromKieModuleFromDrl("test", equalityConfig, drlString);
        } finally {
            System.setProperty("drools.negatable", "off");
        }

        KieSessionConfiguration ksConf = RuleBaseFactory.newKnowledgeSessionConfiguration();
        ((SessionConfiguration) ksConf).setBeliefSystemType( BeliefSystemType.DEFEASIBLE );

        KieSession kSession = kBase.newKieSession( ksConf, null );
        return kSession;
    }


    protected KieSession getSession( String ruleFile ) {
        KieBase kBase;

        try {
            System.setProperty("drools.negatable", "on");
            KieBaseTestConfiguration equalityConfig = TestParametersUtil.getEqualityInstanceOf(kieBaseTestConfiguration);
            kBase = KieBaseUtil.getKieBaseFromClasspathResources(getClass(), equalityConfig, ruleFile);
        } finally {
            System.setProperty("drools.negatable", "off");
        }

        KieSessionConfiguration ksConf = RuleBaseFactory.newKnowledgeSessionConfiguration();
        ((SessionConfiguration) ksConf).setBeliefSystemType( BeliefSystemType.DEFEASIBLE );

        KieSession kSession = kBase.newKieSession( ksConf, null );
        return kSession;
    }


    private void checkStatus( EqualityKey key, int support, DefeasibilityStatus status ) {
        assertEquals( EqualityKey.JUSTIFIED, key.getStatus() );
        BeliefSet set = ((TruthMaintenanceSystemEqualityKey)key).getBeliefSet();
        assertTrue( set instanceof DefeasibleBeliefSet );
        DefeasibleBeliefSet dfs = ( DefeasibleBeliefSet ) set;

        assertEquals( support, dfs.size() );
        assertEquals( status, dfs.getStatus() );
    }



    @Test(timeout = 10000 )
    public void testStrictEntailment() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/strict.drl" );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Ctype = kSession.getKieBase().getFactType( "org.drools.defeasible", "C" );
        FactType Dtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "D" );


        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Ctype.getFactClass() ) {
                checkStatus( key, 2, DefeasibilityStatus.DEFINITELY );
            } else if ( factClass == Dtype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        assertEquals( 5, kSession.getObjects().size() );
    }



    @Test(timeout = 10000 )
    public void testDefeasibleEntailmentWithStrictOverride() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/strictOverride.drl" );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Ctype = kSession.getKieBase().getFactType( "org.drools.defeasible", "C" );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );


        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Ctype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        assertEquals( 5, kSession.getObjects().size() );

    }



    @Test(timeout = 10000 )
    public void defeasibleEntailmentMultiActivation() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/defeat.drl" );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );


        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 3, DefeasibilityStatus.UNDECIDABLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        assertEquals( 3, kSession.getObjects().size() );
    }

    @Test(timeout = 10000 )
    public void testDefeaterNeutrality() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/defeaterOnly.drl" );
        ArrayList list = new ArrayList();
        kSession.setGlobal( "list", list );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );

        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFEATEDLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        for ( Object o : kSession.getObjects() ) {
            System.out.println( o );
        }
        assertEquals( 0, list.size() );
        assertEquals( 1, kSession.getFactCount() );
    }


    @Test(timeout = 10000 )
    public void testMultipleDefeats() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/multiDefeat.drl" );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );


        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 2, DefeasibilityStatus.DEFEATEDLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        for ( Object o : kSession.getObjects() ) {
            System.out.println( o );
        }
        assertEquals( 2, kSession.getObjects().size() );


        kSession.fireAllRules();
    }


    @Test(timeout = 10000 )
    public void testRemoveDefiniteJustifier() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/strictRetract.drl" );

        FactHandle h = kSession.insert( "go" );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Ctype = kSession.getKieBase().getFactType( "org.drools.defeasible", "C" );
        FactType Atype = kSession.getKieBase().getFactType( "org.drools.defeasible", "A" );

        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Ctype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else if ( factClass == Atype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        kSession.retract( h );
        kSession.fireAllRules();

        keys = tms.getEqualityKeyMap();
        iter = keys.iterator();
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Ctype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFEASIBLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

    }

    @Test(timeout = 10000 )
    public void testRemoveDefeasibleJustifier() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/defeaterRetract.drl" );

        FactHandle h = kSession.insert( "go" );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Ctype = kSession.getKieBase().getFactType( "org.drools.defeasible", "C" );
        FactType Atype = kSession.getKieBase().getFactType( "org.drools.defeasible", "A" );

        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Ctype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else if ( factClass == Atype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        kSession.retract( h );
        kSession.fireAllRules();

        keys = tms.getEqualityKeyMap();
        iter = keys.iterator();
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Ctype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFINITELY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

    }



    @Test(timeout = 10000 ) @Ignore
    public void testRemoveDefeasibleEntailmentMultiActivationWithDefeat() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/defeatDefeaterRetract.drl" );
        ArrayList list = new ArrayList();

        kSession.setGlobal( "list", list );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );

        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFEATEDLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        assertEquals( 1, list.size() );
        assertTrue( list.contains( "Stage1" ) );
        assertEquals( 3, kSession.getObjects().size() );

        for ( Object o : kSession.getObjects() ) {
            System.out.println( o );
        }

        FactHandle h = kSession.insert( "go" );
        kSession.fireAllRules();

        keys = tms.getEqualityKeyMap();
        iter = keys.iterator();
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 3, DefeasibilityStatus.DEFEASIBLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        for ( Object o : kSession.getObjects() ) {
            System.out.println( o );
        }

        assertEquals( 5, kSession.getObjects().size() ); // A, A, B, X, GO
        assertEquals( 3, list.size() );
        assertTrue( list.contains( "Stage1" ) );
        assertTrue( list.contains( "Stage2" ) );

        kSession.retract( h );
        kSession.fireAllRules();

        for ( Object o : kSession.getObjects() ) {
            System.out.println( o );
        }

        assertEquals( 3, kSession.getObjects().size() ); // A, A, B, X, GO

        keys = tms.getEqualityKeyMap();
        iter = keys.iterator();
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFEATEDLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }
    }



    @Test(timeout = 10000 )
    public void testDefeaterPositiveVsNegative() {
        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/defeatersPosNeg.drl" );
        ArrayList list = new ArrayList();
        kSession.setGlobal( "list", list );
        kSession.fireAllRules();


        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );

        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Object fact = key.getFactHandle().getObject();
            Class factClass = fact.getClass();
            if ( factClass == Xtype.getFactClass() ) {
                Integer val = (Integer) Xtype.get( fact, "id" );
                switch ( val ) {
                    case -1 :
                        checkStatus( key, 2, DefeasibilityStatus.UNDECIDABLY );
                        break;
                    case 3 :
                        checkStatus( key, 1, DefeasibilityStatus.DEFEATEDLY );
                        break;
                    case -35 :
                        checkStatus( key, 3, DefeasibilityStatus.UNDECIDABLY );
                        break;
                    case 44 :
                        checkStatus( key, 2, DefeasibilityStatus.DEFEASIBLY );
                        break;
                    default : fail( "Unrecognized fact" );
                }
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        assertEquals( 1, list.size() );
        assertTrue( list.contains( -44 ) );
        assertTrue( ! list.contains( -35 ) );
        assertEquals( 2, kSession.getFactCount() );
        assertEquals( 1, getNegativeObjects(kSession).size() );
    }





    @Test(timeout = 10000 )
    public void testDefeatOutcomePosNeg() {

        KieSession kSession = getSession( "org/drools/mvel/compiler/beliefsystem/defeasible/negDefeatPos.drl" );
        ArrayList list = new ArrayList();
        kSession.setGlobal( "list", list );
        kSession.fireAllRules();

        TruthMaintenanceSystem tms = TruthMaintenanceSystemFactory.get().getOrCreateTruthMaintenanceSystem( (ReteEvaluator) kSession );
        FactType Xtype = kSession.getKieBase().getFactType( "org.drools.defeasible", "X" );

        ObjectHashMap keys = tms.getEqualityKeyMap();
        Iterator iter = keys.iterator();
        ObjectHashMap.ObjectEntry entry;
        while ( ( entry = ( ObjectHashMap.ObjectEntry) iter.next() ) != null ) {
            EqualityKey key = (EqualityKey) entry.getValue();

            Class factClass = key.getFactHandle().getObject().getClass();
            if ( factClass == Xtype.getFactClass() ) {
                checkStatus( key, 1, DefeasibilityStatus.DEFEASIBLY );
            } else {
                fail( "Unrecognized object has been logically justified : " + factClass );
            }
        }

        assertEquals( 2, kSession.getObjects().size() );
        assertEquals( 1, getNegativeObjects(kSession).size() );
        assertEquals( 1, list.size() );
        assertTrue( list.contains( "-1" ) );

    }


    @Test(timeout = 10000 )
    public void testPrimeJustificationWithEqualityMode() {
        String droolsSource =
                "package org.drools.tms.test; \n" +
                "declare Bar end \n" +
                "" +
                "declare Holder x : Bar end \n" +
                "" +
                "" +
                "rule Init \n" +
                "when \n" +
                "then \n" +
                "   insert( new Holder( new Bar() ) ); \n" +
                "end \n" +

                "rule Justify \n" +
                "when \n" +
                " $s : Integer() \n" +
                " $h : Holder( $b : x ) \n" +
                "then \n" +
                " insertLogical( $b ); \n" +
                "end \n" +

                "rule React \n" +
                "when \n" +
                " $b : Bar(  ) \n" +
                "then \n" +
                " System.out.println( $b );  \n" +
                "end \n" ;

        KieSession session = getSessionFromString( droolsSource );

        FactHandle handle1 = session.insert( 10 );
        FactHandle handle2 = session.insert( 20 );

        assertEquals( 4, session.fireAllRules() );

        session.delete( handle1 );
        assertEquals( 0, session.fireAllRules() );
    }



    @Test(timeout = 10000 )
    public void testWMStatusOnNegativeDefeat() {
        String droolsSource =
                "package org.drools.tms.test; " +
                "global java.util.List posList;" +
                "global java.util.List negList;" +

                "declare Bar value : int @key end " +

                "rule Top " +
                "@Defeasible " +
                "@Defeats( 'Sub' ) " +
                "when " +
                "   $i : Integer( this < 10 ) " +
                "then " +
                "   insertLogical( new Bar( $i ) ); " +
                "end " +

                "rule Sub " +
                "@Defeasible " +
                "when " +
                "   $i : Integer() " +
                "then " +
                "   insertLogical( new Bar( $i ), $i > 10 ? 'pos' : 'neg' ); " +
                "end " +

                "rule Sup " +
                "@Defeasible " +
                "@Defeats( 'Sub' ) " +
                "when " +
                "   $i : Integer( this > 10 ) " +
                "then " +
                "   insertLogical( new Bar( $i ), 'neg' ); " +
                "end " +

                "rule React_Pos " +
                "when " +
                "   $b : Bar() " +
                "then " +
                "   posList.add( $b ); " +
                "   System.out.println( ' ++++ ' + $b ); " +
                "end " +

                "rule React_Neg " +
                "when " +
                "   $b : Bar( _.neg )" +
                "then " +
                "   negList.add( $b ); " +
                "   System.out.println( ' ---- ' + $b ); " +
                "end " +

                "";

        KieSession session = getSessionFromString( droolsSource );
        List posList = new ArrayList();
        List negList = new ArrayList();
        session.setGlobal( "posList", posList );
        session.setGlobal( "negList", negList );

        session.insert( 20 );
        session.insert( 5 );

        session.fireAllRules();

        assertEquals( 1, posList.size() );
        assertEquals( 1, negList.size() );

        Object posBar = posList.get( 0 );
        InternalFactHandle posHandle = (InternalFactHandle) session.getFactHandle( posBar );
        DefeasibleBeliefSet dbs = (DefeasibleBeliefSet) ((TruthMaintenanceSystemEqualityKey)posHandle.getEqualityKey()).getBeliefSet();
        assertEquals( 1, dbs.size() );
        assertFalse( dbs.isNegated() );
        assertTrue( dbs.isDecided() );
        assertTrue( dbs.isPositive() );

        assertSame( posHandle, dbs.getFactHandle() );
        assertFalse(posHandle.isNegated());
        assertTrue(  dbs.isDefeasiblyPosProveable());
        assertTrue( session.getObjects().contains( posBar ) );

        Object negBar = negList.get( 0 );

        InternalFactHandle negHandle = (InternalFactHandle) getNegativeHandles(session).get(0);
        dbs = (DefeasibleBeliefSet) ((TruthMaintenanceSystemEqualityKey)negHandle.getEqualityKey()).getBeliefSet();
        assertEquals( 1, dbs.size() );
        assertFalse( dbs.isPositive() );
        assertTrue( dbs.isDecided() );
        assertTrue( dbs.isNegated() );

        assertSame( negHandle, dbs.getFactHandle() );
        assertTrue( negHandle.isNegated());

        assertTrue(  dbs.isDefeasiblyNegProveable() );
        assertTrue( session.getObjects().contains( negBar ) );

    }




    @Test
    public void testSelfDefeatWithRebuttal() {
        String droolsSource =
                "package org.drools.tms.test; " +
                "global java.util.List posList;" +
                "global java.util.List negList;" +

                "declare Bar value : int @key end " +

                "rule Create " +
                "@Defeasible " +
                "when " +
                "   $i : Integer() " +
                "then " +
                "   System.out.println( 'Create Bar ' + $i ); " +
                "   bolster( new Bar( $i ) ); " +
                "end " +

                "rule Defeater " +
                "@Direct " +
                "@Defeasible " +
                "@Defeats( 'Create' ) " +
                "when " +
                "   $b1 : Bar( $val1 : value ) " +
                "   $b2 : Bar( $val2 : value > $val1, value - $val1 < 15 ) " +
                "then " +
                "   System.out.println( $b2 + ' defeats ' + $b1 ); " +
                "   bolster( new Bar( $val1 ), 'neg' ); " +
                "end " +

                "rule ReactP " +
                "salience 100 " +
                "when " +
                "   $b : Bar() " +
                "then " +
                "   posList.add( $b ); " +
                "   System.out.println( ' ++++ ' + $b ); " +
                "end " +

                "rule ReactN " +
                "salience 100 " +
                "when " +
                "   $b : Bar( _.neg )  " +
                "then " +
                "   negList.add( $b ); " +
                "   System.out.println( ' ---- ' + $b ); " +
                "end " ;

        KieSession session = getSessionFromString( droolsSource );
        List posList = new ArrayList();
        List negList = new ArrayList();
        session.setGlobal( "posList", posList );
        session.setGlobal( "negList", negList );

        session.insert( 10 );
        session.insert( 30 );
        session.insert( 20 );

        session.fireAllRules();

        assertEquals( 2, posList.size() );
        assertEquals( 1, negList.size() );

    }


    @Test
    public void testDefeatersAndDefeasibles() {
        String droolsSource =
                "package org.drools.tms.test; " +
                "global java.util.List posList;" +

                "declare Bar value : int @key end " +

                "rule B " +
                "@Defeater " +
                "@Defeats( 'C' ) " +
                "when " +
                "   $i : Integer() " +
                "then " +
                "   insertLogical( new Bar( $i ) ); " +
                "end " +

                "rule C " +
                "@Defeasible " +
                "when " +
                "   $i : Integer() " +
                "then " +
                "   insertLogical( new Bar( $i ) ); " +
                "end " +

                "rule React " +
                "when " +
                "   $b : Bar() " +
                "then " +
                "   posList.add( $b ); " +
                "   System.out.println( ' ++++ ' + $b ); " +
                "end " ;

        KieSession session = getSessionFromString( droolsSource );
        List posList = new ArrayList();
        session.setGlobal( "posList", posList );

        session.insert( 10 );

        session.fireAllRules();
        assertEquals( 1, posList.size() );
    }


    @Test(timeout = 10000 )
    public void testManyDefeasibles() {
        String drl = "package org.drools.defeasible; " +
                     "declare Fact " +
                     "     fact: String @key " +
                     "end " +
                     " " +
                     "rule init " +
                     "     when " +
                     "     then " +
                     "         insert( new Fact( 'one' ) ); " +
                     "         insert( new Fact( 'two' ) ); " +
                     "         insert( new Fact( 'two' ) ); " +
                     "end " +
                     " " +
                     "rule rule1 " +
                     "     @Defeasible " +
                     "     enabled true " +
                     "     when " +
                     "         Fact( \"one\"; ) " +
                     "     then " +
                     "         System.out.println(\"one causes wibble\"); " +
                     "         insertLogical( new Fact( \"wibble\") ); " +
                     "end " +
                     " " +
                     "rule rule2 " +
                     "     @Defeasible " +
                     "     when " +
                     "         Fact( \"two\"; ) " +
                     "     then " +
                     "         System.out.println(\"two causes wibble\"); " +
                     "         insertLogical( new Fact( \"wibble\") ); " +
                     "end " +
                     " " +
                     "rule rule3 " +
                     "     @Defeater " +
                     "     @Defeats( \"rule2\" ) " +
                     "     when " +
                     "         Fact( \"two\"; ) " +
                     "     then " +
                     "         System.out.println(\"two negates wibble\"); " +
                     "         insertLogical( new Fact( \"wibble\"), \"neg\" ); " +
                     "end";

        KieSession session = getSessionFromString( drl );
        session.fireAllRules();

        FactType factType = session.getKieBase().getFactType( "org.drools.defeasible", "Fact" );
        for ( Object o : session.getObjects( new ClassObjectFilter( factType.getFactClass() ) ) ) {
            if ( "wibble".equals( factType.get( o, "fact" ) ) ) {
                InternalFactHandle handle = (InternalFactHandle) session.getFactHandle( o );
                DefeasibleBeliefSet dbs = (DefeasibleBeliefSet) ((TruthMaintenanceSystemEqualityKey)handle.getEqualityKey()).getBeliefSet();

                assertEquals( 3, dbs.size() );
                assertTrue( dbs.isConflicting() );
            }
        }

    }


    @Test(timeout = 10000 )
    public void testRetractNegativeDefeaters() {

        String drl = "declare Foo end " +

                     "rule Def " +
                     "  @Defeater " +
                     "when " +
                     "  String() " +
                     "then " +
                     "  insertLogical( new Foo(), 'neg' ); " +
                     "end ";
        KieSession session = getSessionFromString( drl );

        FactHandle h = session.insert( "foo" );

        session.fireAllRules();
        assertEquals( 1, session.getObjects().size() );

        session.delete( h );

        session.fireAllRules();
        assertEquals( 0, session.getObjects().size() );
    }


    public List getNegativeObjects(KieSession kSession) {
        List list = new ArrayList();
        java.util.Iterator it = ((StatefulKnowledgeSessionImpl) kSession).getObjectStore().iterateNegObjects(null);
        while ( it.hasNext() ) {
            list.add(  it.next() );
        }
        return list;
    }

    public List getNegativeHandles(KieSession kSession) {
        List list = new ArrayList();
        java.util.Iterator it = ((StatefulKnowledgeSessionImpl) kSession).getObjectStore().iterateNegFactHandles(null);
        while ( it.hasNext() ) {
            list.add(  it.next() );
        }
        return list;
    }


}
