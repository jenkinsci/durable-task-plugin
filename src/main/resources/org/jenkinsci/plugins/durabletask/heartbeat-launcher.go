package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"
)

func checkHeartbeatErr(err error) {
	checkErr("heartbeat", err)
}

func checkLauncherErr(err error) {
	checkErr("launcher", err)
}

func checkErr(process string, err error) {
	if err != nil {
		errString := fmt.Sprintf("(%v) %v\n", process, err.Error())
		os.Stderr.WriteString(errString)
	}
}

// In the event the launch script is being intentionally terminated, this signal catcher routine
// ensures that the main program will stay alive and record the exit code of the script.
// Without it, there are possible race conditions in which the main program will terminate before
// the script, and thus not record the exit code to the result file
func signalCatcher(sigChan chan os.Signal) {
	for sig := range sigChan {
		// launcher or heartbeat will signal done by closing this channel
		fmt.Printf("(sig catcher) caught: %v\n", sig)
	}
}

func launcher(wg *sync.WaitGroup, pidChan chan int,
	scriptPath string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	scrptCmd := exec.Command("/bin/sh", "-c", scriptPath)
	logFile, err := os.Create(logPath)
	checkLauncherErr(err)
	defer logFile.Close()

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		checkLauncherErr(err)
		defer outputFile.Close()
		scrptCmd.Stdout = outputFile
	} else {
		scrptCmd.Stdout = logFile
	}
	if outputPath != "" {
		// capturing output
		scrptCmd.Stderr = logFile
	} else {
		// Note: pointing to os.Stdout will not capture all err logs and fail unit tests
		scrptCmd.Stderr = scrptCmd.Stdout
	}
	// Prevents script from being terminated if Jenkins gets terminated
	scrptCmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	// Allows child processes of the script to be killed if kill signal sent to script's process group id
	scrptCmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	for i := 0; i < len(scrptCmd.Args); i++ {
		fmt.Printf("launcher args %v: %v\n", i, scrptCmd.Args[i])
	}
	scrptCmd.Start()
	fmt.Printf("launcher: my pid (%v), launcher pid (%v)\n", os.Getpid(), scrptCmd.Process.Pid)
	pidChan <- scrptCmd.Process.Pid
	// Note: If we do not call wait, the forked process will be zombied until the main program exits
	// This will cause the heartbeat goroutine to think that the process has not died
	err = scrptCmd.Wait()
	checkLauncherErr(err)
	resultVal := scrptCmd.ProcessState.ExitCode()
	fmt.Printf("launcher script exit code: %v\n", resultVal)
	fmt.Println("launcher writing result")
	resultFile, err := os.Create(resultPath)
	checkLauncherErr(err)
	defer resultFile.Close()
	resultFile.WriteString(strconv.Itoa(resultVal))
	checkLauncherErr(err)
	fmt.Println("about to close result file")
	err = resultFile.Close()
	checkLauncherErr(err)
	fmt.Println("launcher: done")
}

func heartbeat(wg *sync.WaitGroup, pidChan chan int,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	scriptPid := <-pidChan
	for {
		// send signal 0 because FindProcess will always return true for Unix
		err := syscall.Kill(scriptPid, syscall.Signal(0))
		fmt.Printf("heartbeat: process.Signal on pid %d returned: %v\n", scriptPid, err)
		if err != nil {
			break
		}
		_, err = os.Stat(controlDir)
		if os.IsNotExist(err) {
			break
		}
		_, err = os.Stat(resultPath)
		if !os.IsNotExist(err) {
			break
		}
		// heartbeat
		err = os.Chtimes(logPath, time.Now(), time.Now())
		checkHeartbeatErr(err)
		time.Sleep(time.Second * 3)
	}
}

func main() {
	fmt.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		fmt.Printf("arg %v: %v\n", i, os.Args[i])
	}
	argLen := len(os.Args)
	if argLen < 5 || argLen > 6 {
		// invalid number of args
		os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptPath, outputPath[opt])")
		return
	}
	controlDir := os.Args[1]
	resultPath := os.Args[2]
	logPath := os.Args[3]
	scriptPath := os.Args[4]
	outputPath := ""
	if argLen == 6 {
		outputPath = os.Args[5]
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP, syscall.SIGQUIT)
	go signalCatcher(sigChan)
	// signal.Ignore here will do too good of a job. This will block the stop unit test from being able to
	// terminate the launched script - because mac is killing with SIGINT apparently??
	// signal.Ignore(syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP, syscall.SIGQUIT)

	var wg sync.WaitGroup
	pidChan := make(chan int)
	wg.Add(2)
	go launcher(&wg, pidChan, scriptPath, logPath, resultPath, outputPath)
	go heartbeat(&wg, pidChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	fmt.Println("main: done.")
}
