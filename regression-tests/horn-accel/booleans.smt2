; seahorn-benchmarks/./sv_comp_flat_small/loop-invgen/MADWiFi-encode_ie_ok_true-unreach-call.c.flat_000.smt2
(set-logic HORN)

(declare-fun |main_1| ( Int Int Int Int Bool Int ) Bool)

(assert
  (forall ( (A Int) (B Int) (C Int) (D Bool) (E Int) (v_5 Int) ) 
    (=>
      (and
        (and true (= 0 v_5))
      )
      (main_1 v_5 A B C D E)
    )
  )
)
(assert
  (forall ( (A Int) (B Int) (C Int) (D Bool) (E Bool) (F Bool) (G Bool) (H Bool) (I Bool) (J Bool) (K Bool) (L Bool) (M Bool) (N Bool) (O Int) (P Int) (Q Bool) (R Bool) (S Bool) (T Bool) (U Bool) (V Int) (W Int) (X Int) (Y Bool) (Z Int) (A1 Bool) (B1 Int) (C1 Bool) (D1 Bool) (E1 Bool) (F1 Bool) (G1 Int) (H1 Int) (I1 Int) (v_35 Int) (v_36 Int) ) 
    (=>
      (and
        (main_1 v_35 V W X Y Z)
        (and (= 0 v_35)
     (= O (+ B (* (- 1) B1)))
     (= U (not T))
     (= T (not (<= B1 (- 1))))
     (= S (and J R))
     (= R (not (<= O 2)))
     (= Q (not (<= P O)))
     (= N (or L M))
     (= M (not (<= B1 B)))
     (= L (not K))
     (= K (and I J))
     (= J (not (<= C 0)))
     (= I (and G H))
     (= H (not (<= B 0)))
     (= G (not (<= B1 0)))
     (= F (not (<= 1000000 C)))
     (= E (not (<= 1000000 B)))
     (= D (not (<= 1000000 B1)))
     (or (not C1) (not F1) (= H1 0))
     (or (not C1) (not F1) (= A H1))
     (or (not C1) (not F1) (= I1 G1))
     (or (not C1) (not F1) (= G1 B1))
     (or (not U) (not A1) (not D1))
     (or (not E1) (and C1 F1))
     (or (not C1) (and A1 D1))
     (= E1 true)
     (= S true)
     (not Q)
     (not N)
     (= F true)
     (= E true)
     (= D true)
     (= P (* 2 C))
     (= 1 v_36))
      )
      (main_1 v_36 I1 A C R B)
    )
  )
)
(assert
  (forall ( (A Int) (B Int) (C Bool) (D Bool) (E Bool) (F Bool) (G Bool) (H Bool) (I Bool) (J Bool) (K Bool) (L Bool) (M Bool) (N Int) (O Int) (P Bool) (Q Bool) (R Bool) (S Bool) (T Bool) (U Int) (V Int) (W Int) (X Bool) (Y Int) (Z Bool) (A1 Int) (B1 Bool) (C1 Bool) (v_29 Int) (v_30 Int) ) 
    (=>
      (and
        (main_1 v_29 U V W X Y)
        (and (= 0 v_29)
     (= N (+ A (* (- 1) A1)))
     (= T (not S))
     (= R (and I Q))
     (= Q (not (<= N 2)))
     (= P (not (<= O N)))
     (= J (and H I))
     (= I (not (<= B 0)))
     (= S (not (<= A1 (- 1))))
     (= M (or K L))
     (= L (not (<= A1 A)))
     (= K (not J))
     (= H (and F G))
     (= G (not (<= A 0)))
     (= F (not (<= A1 0)))
     (= E (not (<= 1000000 B)))
     (= D (not (<= 1000000 A)))
     (= C (not (<= 1000000 A1)))
     (or (not Z) T (not C1))
     (or (not B1) (and Z C1))
     (= R true)
     (not P)
     (not M)
     (= E true)
     (= D true)
     (= C true)
     (= B1 true)
     (= O (* 2 B))
     (= 2 v_30))
      )
      (main_1 v_30 U V B Q A)
    )
  )
)
(assert
  (forall ( (A Bool) (B Bool) (C Bool) (D Bool) (E Int) (F Bool) (G Bool) (H Int) (I Int) (J Int) (K Int) (L Bool) (M Int) (N Bool) (O Int) (P Int) (Q Int) (R Bool) (S Bool) (T Int) (U Int) (V Bool) (W Bool) (v_23 Int) (v_24 Int) ) 
    (=>
      (and
        (main_1 v_23 I J K L M)
        (let ((a!1 (or (not R) (= C (not (<= T (- 1))))))
      (a!2 (or (not R) (= A (not (<= K U))))))
  (and (= 1 v_23)
       (= G (not F))
       (= F (not (<= M E)))
       (or (not R) (not W) (= Q O))
       (or (not R) (not W) (= O T))
       (or (not R) (not W) (= H P))
       (or (not R) (not W) (= P U))
       (or (not R) (not D) (not W))
       (or (not G) (not S) (not N))
       (or (not R) (= U (+ 1 J)))
       (or (not R) (= T (+ 2 I)))
       (or (not R) (= D (not C)))
       a!1
       (or (not R) (= B (and A L)))
       a!2
       (or (not R) (and S N))
       (or B (not R))
       (or (not V) (and R W))
       (= V true)
       (= E (+ 1 I))
       (= 1 v_24)))
      )
      (main_1 v_24 Q H K L M)
    )
  )
)
(assert
  (forall ( (A Bool) (B Bool) (C Bool) (D Bool) (E Int) (F Bool) (G Bool) (H Int) (I Int) (J Int) (K Bool) (L Int) (M Bool) (N Bool) (O Bool) (P Bool) (Q Int) (R Int) (S Bool) (T Bool) (U Bool) (V Bool) (v_22 Int) (v_23 Int) ) 
    (=>
      (and
        (main_1 v_22 H I J K L)
        (let ((a!1 (or (not O) (= C (not (<= Q (- 1))))))
      (a!2 (or (not O) (= A (not (<= J R))))))
  (and (= 1 v_22)
       (= G (not F))
       (= F (not (<= L E)))
       (or (not S) (and U M) (and O T))
       (or (not G) (not P) (not M))
       (or D (not O) (not T))
       (or (not U) G (not M))
       (or (not O) (= Q (+ 2 H)))
       (or (not O) (= R (+ 1 I)))
       a!1
       (or (not O) (= B (and A K)))
       (or (not O) (= D (not C)))
       a!2
       (or (not O) (and P M))
       (or B (not O))
       (or (not N) (and S V))
       (= N true)
       (= E (+ 1 H))
       (= 2 v_23)))
      )
      (main_1 v_23 H I J K L)
    )
  )
)
(assert
  (forall ( (A Int) (B Int) (C Int) (D Bool) (E Int) (v_5 Int) ) 
    (=>
      (and
        (main_1 v_5 A B C D E)
        (= 2 v_5)
      )
      false
    )
  )
)

(check-sat)
(exit)