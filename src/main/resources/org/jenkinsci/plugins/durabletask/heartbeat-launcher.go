package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"sync"

	"golang.org/x/sys/unix"
)

func checkHeartbeatErr(err error) {
	checkErr("heartbeat", err)
}

func checkLauncherErr(err error) {
	checkErr("launcher", err)
}

func checkErr(process string, err error) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "(%v) %v\n", process, err.Error())
	}
}

// In the event the launch script is being intentionally terminated, this signal catcher routine
// ensures that the main program will stay alive and record the exit code of the script.
// Without it, there are possible race conditions in which the main program will terminate before
// the script, and thus not record the exit code to the result file
func signalCatcher(sigChan chan os.Signal, pidChan chan int) {
	scriptPid := <-pidChan
	for sig := range sigChan {
		fmt.Printf("(sig catcher) caught: %v\n", sig)
		switch sig {
		case unix.SIGTERM:
			// TODO: caught sigterm, forward as sigint?
			fmt.Printf("(sigcatcher) sending sigterm to %v\n", scriptPid)
			unix.Kill(scriptPid, unix.SIGTERM)
		}
	}
}

func launcher(wg *sync.WaitGroup, pidChan chan int,
	scriptString string, logPath string, resultPath string, outputPath string) {

	defer wg.Done()
	recordExit := fmt.Sprintf("; echo $? > %v.tmp; mv %v.tmp %v; wait", resultPath, resultPath, resultPath)
	scriptWithExit := scriptString + recordExit
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
	pidChan <- scriptCmd.Process.Pid
	fmt.Printf("(launcher) my pid (%v), launcher pid (%v)\n", os.Getpid(), scriptCmd.Process.Pid)
	// Note: If we do not call wait, the forked process will be zombied until the main program exits
	// ignoring SIGCHLD is not as portable as calling Wait()
	err = scriptCmd.Wait()
	checkLauncherErr(err)
	resultVal := scriptCmd.ProcessState.ExitCode()
	fmt.Printf("(launcher) script exit code: %v\n", resultVal)
}

func heartbeat(wg *sync.WaitGroup, pidChan chan int,
	controlDir string, resultPath string, logPath string) {

	defer wg.Done()
	const HBSCRIPT string = "heartbeat.sh"
	scriptPid := <-pidChan
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
	// heartbeat script
	heartbeat := fmt.Sprintf("while true ; do kill -0 %v; if [ $? -ne 0 ]; then echo '(heartbeat) process (%v) not found'; break; fi; touch %v; echo touched log; sleep 3; done",
		scriptPid, scriptPid, logPath)
	heartbeatScript, err := os.Create(HBSCRIPT)
	checkHeartbeatErr(err)
	heartbeatScript.WriteString(heartbeat)
	heartbeatScript.Close()

	// heartbeatCmd := exec.Command("/bin/sh", heartbeat)
	heartbeatCmd := exec.Command("/bin/sh", HBSCRIPT)
	heartbeatCmd.Stdout = os.Stdout
	heartbeatCmd.Stderr = os.Stderr
	heartbeatCmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
	heartbeatCmd.Start()
	fmt.Printf("(heartbeat) my pid (%v), heartbeat pid (%v)\n", os.Getpid(), heartbeatCmd.Process.Pid)
	err = heartbeatCmd.Wait()
	checkHeartbeatErr(err)
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
	controlDir := os.Args[1]
	resultPath := os.Args[2]
	logPath := os.Args[3]
	scriptString := os.Args[4]
	outputPath := ""
	if argLen == 6 {
		outputPath = os.Args[5]
	}

	pidChan := make(chan int)
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, unix.SIGTERM)
	signal.Ignore(unix.SIGINT, unix.SIGHUP, unix.SIGQUIT)
	go signalCatcher(sigChan, pidChan)

	var wg sync.WaitGroup
	wg.Add(2)
	go launcher(&wg, pidChan, scriptString, logPath, resultPath, outputPath)
	go heartbeat(&wg, pidChan, controlDir, resultPath, logPath)
	wg.Wait()
	signal.Stop(sigChan)
	close(sigChan)
	fmt.Println("(main) done.")
}
