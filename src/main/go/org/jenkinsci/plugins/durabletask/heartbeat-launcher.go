package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"sync"

	"golang.org/x/sys/unix"
)

// Note: EXIT signal is needed as some shell variants require an EXIT trap after catching a signal
const trapSig = "trap ':' INT TERM EXIT"

func checkHeartbeatErr(err error) {
	checkErr("heartbeat", err)
}

func checkLauncherErr(err error) {
	checkErr("launcher", err)
}

func checkErr(process string, err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "(%v) check err: %v\n", process, err.Error())
	}
}

// Catch a termination signal to allow for a graceful exit (i.e. no zombies)
func signalCatcher(sigChan chan os.Signal) {
	for sig := range sigChan {
		fmt.Printf("(sig catcher) caught: %v\n", sig)
	}
}

func launcher(wg *sync.WaitGroup, pidChan chan int,
	scriptString string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	recordExit := fmt.Sprintf("status=\"$?\"; echo \"$status\" > %v.tmp; mv %v.tmp %v; wait; exit \"$status\"",
		resultPath, resultPath, resultPath)
	scriptWithExit := trapSig + "; " + scriptString + "; " + recordExit
	scriptCmd := exec.Command("/bin/sh", "-c", scriptWithExit)
	logFile, err := os.Create(logPath)
	checkLauncherErr(err)
	defer logFile.Close()

	if outputPath != "" {
		// capturing output
		outputFile, err := os.Create(outputPath)
		checkLauncherErr(err)
		defer outputFile.Close()
		scriptCmd.Stdout = outputFile
	} else {
		scriptCmd.Stdout = logFile
	}
	if outputPath != "" {
		// capturing output
		scriptCmd.Stderr = logFile
	} else {
		// Note: pointing to os.Stdout will not capture all err logs and fail unit tests
		scriptCmd.Stderr = scriptCmd.Stdout
	}
	// Prevents script from being terminated if program gets terminated
	scriptCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	for i := 0; i < len(scriptCmd.Args); i++ {
		fmt.Printf("(launcher) args %v: %v\n", i, scriptCmd.Args[i])
	}
	scriptCmd.Start()
	pid := scriptCmd.Process.Pid
	pidChan <- pid
	fmt.Printf("(launcher) launched %v\n", pid)
	err = scriptCmd.Wait()
	checkLauncherErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	fmt.Printf("(launcher) script exit code: %v\n", resultVal)
}

func heartbeat(wg *sync.WaitGroup, launchedPid int,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	const HBSCRIPT string = "heartbeat.sh"
	fmt.Printf("(heartbeat) checking if %v is alive\n", launchedPid)
	_, err := os.Stat(controlDir)
	if os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "%v\n", err.Error())
		return
	}
	_, err = os.Stat(resultPath)
	if !os.IsNotExist(err) {
		fmt.Printf("Result file already exists, stopping heartbeat.\n%v\n", resultPath)
		return
	}
	// create the heartbeat script
	heartbeat := fmt.Sprintf("#! /bin/sh\n%v; pid=\"$$\"; echo \"(heartbeat) pid: \"$pid\"\"; while true ; do kill -0 %v; status=\"$?\"; if [ \"$status\" -ne 0 ]; then break; fi; echo \"(heartbeat)(\"$pid\") found %v\"; touch %v; sleep 3; done; echo \"(\\\"$pid\\\") exiting\"; exit",
		trapSig, launchedPid, launchedPid, logPath)
	heartbeatPath := controlDir + HBSCRIPT
	heartbeatScript, err := os.Create(heartbeatPath)
	checkHeartbeatErr(err)
	err = os.Chmod(heartbeatPath, 0755)
	checkHeartbeatErr(err)
	heartbeatScript.WriteString(heartbeat)
	heartbeatScript.Close()

	heartbeatCmd := exec.Command(heartbeatPath)
	/************************************
	// Warning: DO NOT set cmd.Stdout/StdErr is set to os.Stdout/Stderr
	// If you do, the heartbeat thread will not survive jenkins termination
	///////////// DO NOT DO THIS ///////////////
	// heartbeatCmd.Stdout = os.Stdout
	// heartbeatCmd.Stderr = os.Stderr
	************************************************/
	// logFile, logErr := os.Create(controlDir + "heartbeat.log")
	// checkLauncherErr(logErr)
	// defer logFile.Close()
	// heartbeatCmd.Stdout = logFile
	// heartbeatCmd.Stderr = logFile
	heartbeatCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	heartbeatCmd.Start()
	pid := heartbeatCmd.Process.Pid
	fmt.Printf("(heartbeat) heartbeat pid (%v)\n", pid)
	err = heartbeatCmd.Wait()
	checkHeartbeatErr(err)
	fmt.Printf("(heartbeat) exit\n")
}

func main() {
	fmt.Printf("number of args: %v\n", os.Args)
	for i := 0; i < len(os.Args); i++ {
		fmt.Printf("arg %v: %v\n", i, os.Args[i])
	}
	argLen := len(os.Args)
	if argLen < 5 || argLen > 6 {
		// invalid number of args
		os.Stderr.WriteString("Input error: expected number of args is 5 or 6 (controlDir, resultPath, logPath, scriptString, outputPath[opt])")
		return
	}
	ppid := os.Getppid()
	fmt.Printf("Parent pid is: %v\n", ppid)
	controlDir := os.Args[1]
	resultPath := os.Args[2]
	logPath := os.Args[3]
	scriptString := os.Args[4]
	outputPath := ""
	if argLen == 6 {
		outputPath = os.Args[5]
	}

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGINT, unix.SIGTERM)
	go signalCatcher(sigChan)

	pidChan := make(chan int)
	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, pidChan, scriptString, logPath, resultPath, outputPath)
	launchedPid := <-pidChan
	go heartbeat(&wg, launchedPid, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	fmt.Println("(main) done.")
}
