package com.example.jenkinsprimer.test;

import org.junit.Test;

public class DelayTest {

  @Test
  public void introduceDelay() throws Exception {
    /*
     * Because this project is so incredibly fast, we need to introduce a delay because the junit
     * archiver will fail otherwise
     */
    Thread.sleep(10_000);
  }

}
