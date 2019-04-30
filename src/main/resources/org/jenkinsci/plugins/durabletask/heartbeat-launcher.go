package main

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"time"
)

func launcher(scriptPath string, logPath string, resultPath string, capture bool, pidChan chan int) {
	scrptCmd := exec.Command("/bin/sh", "-c", scriptPath)
	// scrptCmd.Stdout, err = os.OpenFile(logFile, os.O_WRONLY, 0644) //os.Stdout
	logFile, err := os.Create(logPath)
	if err != nil {
		os.Stderr.WriteString(err.Error())
		return
	}
	scrptCmd.Stdout = logFile //os.Stdout
	// TODO: error is here. If capture ouput is enabled, then can pass configuredInterpreter unit test
	// scrptCmd.Stderr = logFile
	if capture {
		scrptCmd.Stderr = logFile
	} else {
		scrptCmd.Stderr = os.Stdout //os.Stderr
	}
	scrptCmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	for i := 0; i < len(scrptCmd.Args); i++ {
		fmt.Printf("launcher args %v: %v\n", i, scrptCmd.Args[i])
	}
	scrptCmd.Start()
	pidChan <- scrptCmd.Process.Pid
	// If we do not call wait here, the forked process will be zombied until the main program exits
	err = scrptCmd.Wait()
	if err != nil {
		errString := fmt.Sprintf("launcher exit error: %v\n", err.Error())
		os.Stderr.WriteString(errString)
	}
	resultVal := scrptCmd.ProcessState.ExitCode()
	// fmt.Printf("launcher script exit code: %v\n", resultVal)
	// result, err := os.OpenFile(resultFile, os.O_WRONLY, 0644)
	result, err := os.Create(resultPath)
	result.WriteString(strconv.Itoa(resultVal))
	if err != nil {
		os.Stderr.WriteString(err.Error())
		return
	}
	fmt.Println("launcher: done")
}

func main() {
	fmt.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		fmt.Printf("arg %v: %v\n", i, os.Args[i])
	}
	if len(os.Args) != 6 {
		// invalid number of args
		os.Stderr.WriteString("Input error: expected number of args is 5 (controlDir, resultFile, logFile, scriptPath, capturingOutput)")
		return
	}
	controlDir := os.Args[1]
	resultFile := os.Args[2]
	logFile := os.Args[3]
	scriptFile := os.Args[4]
	capturingOutput := os.Args[5]
	capture, err := strconv.ParseBool(capturingOutput)
	if err != nil {
		os.Stderr.WriteString(err.Error())
	}

	pidChan := make(chan int)
	go launcher(scriptFile, logFile, resultFile, capture, pidChan)
	scriptPid := <-pidChan

	for {
		// send signal 0 because FindProcess will always return true for Unix
		err := syscall.Kill(scriptPid, syscall.Signal(0))
		fmt.Printf("process.Signal on pid %d returned: %v\n", scriptPid, err)
		if err != nil {
			break
		}
		_, err = os.Stat(controlDir)
		if os.IsNotExist(err) {
			break
		}
		_, err = os.Stat(resultFile)
		if !os.IsNotExist(err) {
			break
		}
		// heartbeat
		err = os.Chtimes(logFile, time.Now(), time.Now())
		if err != nil {
			fmt.Printf("Chtimes error: %d", err)
		}
		time.Sleep(time.Second * 3)
	}
	fmt.Println("main: done.")
}
